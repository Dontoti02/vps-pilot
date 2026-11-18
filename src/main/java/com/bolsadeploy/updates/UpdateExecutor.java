package com.bolsadeploy.updates;

import com.bolsadeploy.backups.BackupService;
import com.bolsadeploy.deployment.CodigoStage;
import com.bolsadeploy.deployment.DependenciasAppStage;
import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.LaravelEnvStage;
import com.bolsadeploy.deployment.MigracionStage;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.domain.BackupRecord;
import com.bolsadeploy.domain.UpdatePlan;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.security.CommandValidator;

/**
 * Ejecuta una actualización de una institución (FASE E) con la secuencia:
 *
 * <pre>
 * aprobación de riesgo → respaldo previo → release nueva (clone del commit)
 * → composer/npm build → migrate --force → conmutación atómica (ln -sfn)
 * → validación real por HTTP+contenido → [rollback automático si falla]
 * → limpieza de releases antiguas
 * </pre>
 *
 * Garantías:
 * <ul>
 *   <li><b>Nunca</b> se ejecuta {@code migrate:fresh} ni {@code db:wipe}.</li>
 *   <li><b>Nunca</b> se regenera {@code APP_KEY} ni se toca {@code shared/.env}
 *       (los enlaces apuntan a shared: la actualización no reescribe config).</li>
 *   <li>La conmutación es un solo {@code ln -sfn} atómico sobre {@code current}.</li>
 *   <li>Si la validación falla, {@code current} vuelve a la release anterior y
 *       se comprueba que el sitio responde de nuevo.</li>
 *   <li>Si algo falla antes de conmutar, el sitio sigue sirviendo la versión
 *       anterior (la release incompleta se elimina).</li>
 * </ul>
 */
public class UpdateExecutor {

    /** Resultado verificable de una actualización. */
    public record Resultado(boolean exito, String mensaje, String commitAnterior,
                            String commitNuevo, boolean rollbackRealizado,
                            String ubicacionRespaldo) {
    }

    /**
     * @param plan            plan mostrado y aceptado por el usuario
     * @param aprobadoRiesgo  true si el usuario aprobó un plan destructivo
     * @param backups         servicio de respaldos (respaldo previo)
     */
    public Resultado ejecutar(DeploymentContext ctx, UpdatePlan plan, boolean aprobadoRiesgo,
                              BackupService backups) throws StageException {
        if (plan == null) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, "No hay plan de actualización.");
        }
        if (plan.requiereAprobacionExplicita() && !aprobadoRiesgo) {
            throw new StageException(StageException.Tipo.REQUIERE_APROBACION,
                    "Esta actualización toca migraciones/archivos destructivos y NO fue aprobada.\n"
                            + plan.resumenCambios());
        }
        String objetivo = plan.commitObjetivo();
        if (!CommandValidator.esSha(objetivo)) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "El commit objetivo no es válido: " + objetivo);
        }

        String slug = ctx.institucion.getSlug();
        String dominio = ctx.institucion.dominioPrincipal();
        String current = ctx.rutaReleaseActual();
        String releases = ctx.rutaBase() + "/releases";
        boolean forzar = "true".equalsIgnoreCase(ctx.valores.get("forzarReinstalacion"));

        // -------------------------------------------------- Entorno y Nginx previo
        NginxSyncHelper.inicializarEntorno(ctx);
        NginxSyncHelper.NginxEstado nginxAntes = NginxSyncHelper.inspeccionarNginx(ctx, dominio);

        // -------------------------------------------------- Release anterior
        String anterior = releaseActual(ctx);
        ctx.valores.put("releaseAnterior", anterior);
        ctx.valores.put("commitAnterior", anterior);
        if (objetivo.equalsIgnoreCase(anterior) && !forzar) {
            ctx.log("  El enlace current ya apunta a " + corto(objetivo)
                    + "; verificando sincronización de Nginx, dependencias, permisos y cachés…");
            String releaseExistente = releases + "/" + anterior;
            ctx.valores.put("releasePath", releaseExistente);
            ctx.valores.put("releaseSha", anterior);
            NginxSyncHelper.adoptarDesdeRaizPreviaSiNecesario(ctx, nginxAntes);
            prepararEnlaces(ctx, releaseExistente);

            CommandResult faltaDeps = ctx.ejecutar(
                    "{ test -f " + releaseExistente + "/composer.json && ! test -f " + releaseExistente + "/vendor/autoload.php; } "
                            + "|| { test -f " + releaseExistente + "/package.json && ! test -d " + releaseExistente + "/node_modules; } && echo SI", 15);
            if ("SI".equals(faltaDeps.primeraLinea().trim())) {
                ctx.log("  [i] Faltan dependencias en la release actual; instalando/compilando…");
                new DependenciasAppStage().ejecutar(ctx);
            }

            asegurarAppKeyLaravelSiFalta(ctx, releaseExistente);
            CodigoStage.configurarPublic(ctx, releaseExistente);
            new MigracionStage().ejecutar(ctx);
            boolean nginxAlineado = NginxSyncHelper.asegurarNginxApuntaACurrent(ctx, nginxAntes);
            NginxSyncHelper.recargarServiciosPostConmutacion(ctx);
            NginxSyncHelper.NginxEstado nginxFinal = NginxSyncHelper.inspeccionarNginx(ctx, dominio);
            String fallo = NginxSyncHelper.validarHttpConCanary(ctx, nginxFinal, objetivo);
            if (fallo != null) {
                throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                        "El commit " + corto(objetivo) + " estaba en current, pero la verificación en vivo detectó un problema: "
                                + fallo);
            }
            String msg = nginxAlineado
                    ? "Sincronización aplicada sobre " + corto(objetivo)
                    + ": se corrigió la ruta/caché de Nginx y se recargaron los servicios."
                    : "Ya está desplegado el commit " + corto(objetivo)
                    + " (Nginx, cachés y servicios verificados en vivo).";
            ctx.ok(msg);
            return new Resultado(true, msg, anterior, objetivo, false, "");
        }

        // Adoptar .env / storage / uploads de una web previa manual si aún no existen en shared/
        NginxSyncHelper.adoptarDesdeRaizPreviaSiNecesario(ctx, nginxAntes);

        // ----------------------------------------------------- Respaldo previo
        String respaldo = "";
        if (ctx.ajustes.isRespaldoPreActualizacion()) {
            ctx.log("  Respaldo previo obligatorio antes de actualizar…");
            BackupRecord b = backups.crear(ctx, BackupService.Tipo.INTEGRAL, anterior);
            if (!"OK".equals(b.getVerificacion())) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "El respaldo previo NO superó la verificación (" + b.getTamano()
                                + "). No se actualiza sin respaldo válido.");
            }
            respaldo = b.getUbicacion();
            ctx.ok("Respaldo previo verificado: " + respaldo);
        } else {
            ctx.log("  [!] Respaldo previo desactivado en Configuración; se continúa sin él.");
        }

        // ----------------------------------------------------- Release nueva
        String nueva = releases + "/" + objetivo;
        ctx.log("  Preparando release " + corto(objetivo) + "…");
        try {
            clonarEn(ctx, objetivo, nueva);
            prepararEnlaces(ctx, nueva);

            // Dependencias PHP y frontend (reutiliza la etapa ya probada)
            ctx.valores.put("releasePath", nueva);
            ctx.valores.put("releaseSha", objetivo);
            new DependenciasAppStage().ejecutar(ctx);

            // Asegurar APP_KEY válida en Laravel si el .env fue recién creado o estaba vacío
            asegurarAppKeyLaravelSiFalta(ctx, nueva);

            // Resolver raíz pública real DESPUÉS de compilar frontend (dist/build/out/public/.)
            CodigoStage.configurarPublic(ctx, nueva);

            // Migraciones + cachés + permisos sobre la release NUEVA
            new MigracionStage().ejecutar(ctx);

        } catch (StageException e) {
            if (!nueva.equals(releases + "/" + anterior)) {
                ctx.ejecutar("rm -rf " + nueva, 120); // no dejar releases a medias ni borrar la actual si es reinstalación
            }
            String extra = e.getTipo() == StageException.Tipo.REQUIERE_APROBACION
                    ? "\nAtención: las migraciones pueden haberse aplicado parcialmente sobre la base. "
                    + "Restaure el respaldo previo " + (respaldo.isBlank() ? "(no se creó)" : respaldo)
                    + " si el sitio deja de funcionar."
                    : "";
            throw new StageException(e.getTipo(),
                    "Falló la preparación de la actualización (el sitio sigue en " + corto(anterior) + "): "
                            + e.getMessage() + extra, e);
        }

        // ----------------------------------------------- Conmutación atómica
        ctx.ejecutar("test -d " + current + " && ! test -L " + current + " && rm -rf " + current + " || true", 30);
        CommandResult enlace = ctx.ejecutar(
                "ln -sfn " + nueva + " " + current + " && readlink -f " + current, 60);
        String trasEnlace = enlace.primeraLinea().trim();
        if (!trasEnlace.equals(nueva)) {
            if (!anterior.isBlank()) {
                ctx.ejecutar("ln -sfn " + releases + "/" + anterior + " " + current, 60);
            }
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "La conmutación de 'current' no quedó donde se esperaba (leído: '"
                            + trasEnlace + "'). Se restauró la versión anterior.");
        }
        ctx.ok("current → releases/" + corto(objetivo) + " (conmutación atómica)");

        // ---------------------------------------- Nginx + Cachés + Servicios
        NginxSyncHelper.asegurarNginxApuntaACurrent(ctx, nginxAntes);
        NginxSyncHelper.recargarServiciosPostConmutacion(ctx);

        // ------------------------------------------------------ Validación en vivo
        NginxSyncHelper.NginxEstado nginxFinal = NginxSyncHelper.inspeccionarNginx(ctx, dominio);
        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, nginxFinal, objetivo);
        if (fallo != null) {
            ctx.log("  [X] La verificación en vivo falló: " + fallo);
            if (fallo.startsWith("NGINX_MISMATCH:")) {
                throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                        fallo.replace("NGINX_MISMATCH: ", "")
                                + " (El código se descargó en " + current + ", pero Nginx sigue apuntando a otra ruta).");
            }
            boolean rollbackOk = retroceder(ctx, releases, current, anterior);
            if (rollbackOk) {
                String falloTras = NginxSyncHelper.validarHttpConCanary(ctx, nginxFinal, anterior);
                if (falloTras != null && !falloTras.startsWith("NGINX_MISMATCH:")) {
                    throw new StageException(StageException.Tipo.FATAL,
                            "ROLLBACK incompleto: se volvió a " + corto(anterior)
                                    + " pero el sitio tampoco responde bien: " + falloTras
                                    + ". Intervención manual requerida."
                                    + (respaldo.isBlank() ? "" : " Respaldo previo: " + respaldo));
                }
                throw new StageException(StageException.Tipo.REQUIERE_APROBACION,
                        "La versión " + corto(objetivo) + " se descartó y el sitio volvió a "
                                + corto(anterior) + " (validación fallida: " + fallo + ")."
                                + (respaldo.isBlank() ? "" : " Respaldo previo: " + respaldo));
            }
            throw new StageException(StageException.Tipo.FATAL,
                    "La validación falló (" + fallo + ") y el ROLLBACK a " + corto(anterior)
                            + " también. Reintroduzca manualmente el enlace current con: ln -sfn "
                            + releases + "/" + anterior + " " + current
                            + (respaldo.isBlank() ? "" : ". Respaldo previo: " + respaldo));
        }

        // ------------------------------------------------- Limpieza y éxito
        limpiarReleases(ctx, releases, nueva);
        ctx.valores.put("releasePath", nueva);
        ctx.valores.put("releaseSha", objetivo);
        ctx.ok("Actualización validada: " + corto(anterior) + " → " + corto(objetivo));
        return new Resultado(true,
                "Actualizada a " + corto(objetivo) + " (" + plan.migracionesPendientes().size()
                        + " migración(es) pendiente(s) aplicadas). Verificada en servidor web y servicios recargados."
                        + (respaldo.isBlank() ? "" : " Respaldo previo: " + respaldo),
                anterior, objetivo, false, respaldo);
    }

    // ------------------------------------------------------------------
    // Pasos
    // ------------------------------------------------------------------

    /** SHA de la release a la que apunta current (vacío si no hay sitio). */
    static String releaseActual(DeploymentContext ctx) throws StageException {
        CommandResult r = ctx.ejecutar(
                "readlink -f " + ctx.rutaReleaseActual() + " 2>/dev/null || true", 30);
        String ruta = r.primeraLinea().trim();
        if (ruta.isBlank()) {
            return "";
        }
        String nombre = ruta.substring(ruta.lastIndexOf('/') + 1);
        return CommandValidator.esSha(nombre) ? nombre : "";
    }

    /** Clona el repo en destino dejándolo en el commit objetivo exacto. */
    private void clonarEn(DeploymentContext ctx, String sha, String destino) throws StageException {
        String repo = ctx.institucion.repositorioEfectivo(ctx.ajustes);
        String url = CommandValidator.resolverUrlGit(repo, ctx.ajustes.getGithubToken());
        String rama = ctx.institucion.getRama() != null && !ctx.institucion.getRama().isBlank()
                ? ctx.institucion.getRama() : ctx.ajustes.getRamaDefault();
        CommandValidator.exigirGitRef(rama);
        String tmp = "/tmp/vpspilot-upd-" + ctx.institucion.getSlug();

        ctx.log("  Clonando " + repo + " (rama " + rama + ")…");
        ctx.exigirExito("rm -rf " + tmp + " && git clone --depth 50 --branch "
                + CommandValidator.escaparShell(rama) + " " + CommandValidator.escaparShell(url)
                + " " + tmp, 900, "git clone");

        CommandResult sel = ctx.ejecutar(
                "cd " + tmp + " && { git cat-file -e " + sha + "^{commit} 2>/dev/null"
                        + " || git fetch --depth 1 origin " + sha + "; }"
                        + " && git -c advice.detachedHead=false checkout -q --detach " + sha, 600);
        if (!sel.exito()) {
            ctx.ejecutar("rm -rf " + tmp, 60);
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo obtener el commit " + corto(sha) + ": " + recortar(sel.salida()));
        }

        CommandResult rev = ctx.ejecutar("cd " + tmp + " && git rev-parse HEAD", 60);
        String obtenido = rev.primeraLinea().trim();
        boolean coincide = CommandValidator.esSha(obtenido) && (
                obtenido.equalsIgnoreCase(sha)
                        || obtenido.toLowerCase().startsWith(sha.toLowerCase())
                        || sha.toLowerCase().startsWith(obtenido.toLowerCase())
        );

        if (!coincide) {
            ctx.ejecutar("rm -rf " + tmp, 60);
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "git checkout terminó en " + corto(obtenido) + " y no en " + corto(sha) + ".");
        }

        CommandResult mover = ctx.ejecutar(
                "rm -rf " + tmp + "/.git && mkdir -p " + destino.substring(0, destino.lastIndexOf('/'))
                        + " && rm -rf " + destino + " && mv " + tmp + " " + destino, 300);
        if (!mover.exito()) {
            ctx.ejecutar("rm -rf " + tmp, 60);
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo mover la release: " + recortar(mover.salida()));
        }
        ctx.ok("Release clonada en releases/" + corto(sha));
    }

    /** Enlaces persistentes y propietario (sin fijar todavía public/ hasta finalizar build). */
    private void prepararEnlaces(DeploymentContext ctx, String release) throws StageException {
        String shared = ctx.rutaShared();
        ctx.valores.put("releasePath", release);

        // Si shared/.env no existe o está vacío (0 bytes), generarlo de .env.example / proyecto sin sobreescribir uno válido
        CommandResult envValido = ctx.ejecutar("test -s " + shared + "/.env && echo SI", 15);
        if (!"SI".equals(envValido.primeraLinea().trim())) {
            CommandResult usaEnv = ctx.ejecutar(
                    "{ test -f " + release + "/artisan || test -f " + release + "/.env.example || { test -f " + release + "/.env && ! test -L " + release + "/.env; }; } && echo SI", 15);
            if ("SI".equals(usaEnv.primeraLinea().trim())) {
                ctx.ejecutar("test -e " + shared + "/.env && ! test -s " + shared + "/.env && rm -f " + shared + "/.env || true", 15);
                boolean previoAct = ctx.esActualizacion;
                ctx.esActualizacion = true; // no correr key:generate aún antes de composer install
                try {
                    new LaravelEnvStage().ejecutar(ctx);
                } finally {
                    ctx.esActualizacion = previoAct;
                }
            }
        }
        ctx.ejecutar("test -f " + shared + "/.env && rm -f " + release + "/.env && ln -sfn " + shared + "/.env " + release + "/.env || true", 30);

        CommandResult tieneStorage = ctx.ejecutar("{ test -d " + release + "/storage || test -f " + release + "/artisan; } && echo SI", 30);
        if ("SI".equals(tieneStorage.primeraLinea().trim())) {
            ctx.ejecutar("mkdir -p " + shared + "/storage/logs " + shared + "/storage/framework/cache/data "
                    + shared + "/storage/framework/sessions " + shared + "/storage/framework/views "
                    + shared + "/storage/app/public", 30);
            ctx.ejecutar("test -d " + release + "/storage && ! test -L " + release + "/storage && cp -rn "
                    + release + "/storage/. " + shared + "/storage/ 2>/dev/null || true", 60);
            ctx.ejecutar("rm -rf " + release + "/storage && ln -sfn " + shared + "/storage " + release + "/storage", 30);
            ctx.ejecutar(ctx.sudo("chown -R www-data:www-data " + shared + "/storage && chmod -R 775 " + shared + "/storage"), 60);
        }

        CommandResult tieneUploads = ctx.ejecutar("test -d " + release + "/public/uploads && echo SI", 30);
        if ("SI".equals(tieneUploads.primeraLinea().trim())) {
            ctx.ejecutar("mkdir -p " + shared + "/uploads", 30);
            ctx.ejecutar("! test -L " + release + "/public/uploads && cp -rn "
                    + release + "/public/uploads/. " + shared + "/uploads/ 2>/dev/null || true", 60);
            ctx.ejecutar("rm -rf " + release + "/public/uploads && ln -sfn " + shared + "/uploads " + release + "/public/uploads", 30);
            ctx.ejecutar(ctx.sudo("chown -R www-data:www-data " + shared + "/uploads && chmod -R 775 " + shared + "/uploads"), 60);
        }

        ctx.ejecutar("test -d " + release + "/bootstrap && mkdir -p " + release + "/bootstrap/cache", 30);
        ctx.exigirExito(ctx.sudo("chown -R www-data:www-data " + release), 300,
                "Propietario www-data de la release");
    }

    /** Si la app es Laravel y shared/.env no tiene APP_KEY válida, la genera una vez instalado vendor/. */
    private void asegurarAppKeyLaravelSiFalta(DeploymentContext ctx, String release) throws StageException {
        String sharedEnv = ctx.rutaShared() + "/.env";
        CommandResult necesita = ctx.ejecutar(
                "test -f " + release + "/artisan && test -f " + release + "/vendor/autoload.php"
                        + " && ! grep -q '^APP_KEY=base64:' " + sharedEnv + " 2>/dev/null && echo SI", 15);
        if ("SI".equals(necesita.primeraLinea().trim())) {
            ctx.log("  [i] Generando APP_KEY faltante en shared/.env…");
            ctx.ejecutar("grep -q '^APP_KEY=' " + sharedEnv + " 2>/dev/null || echo 'APP_KEY=' >> " + sharedEnv, 15);
            ctx.ejecutar(ctx.sudo("chown www-data:www-data " + sharedEnv + " && chmod 660 " + sharedEnv), 15);
            CommandResult gen = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan key:generate --force", 60);
            ctx.ejecutar(ctx.sudo("chmod 640 " + sharedEnv), 15);
            if (gen.exito()) {
                ctx.ok("APP_KEY generada en shared/.env");
            } else {
                ctx.log("  [!] No se pudo generar APP_KEY: " + recortar(gen.salida()));
            }
        }
    }

    /** Vuelve a apuntar current a la release anterior, recarga servicios y verifica. */
    private boolean retroceder(DeploymentContext ctx, String releases, String current, String anterior)
            throws StageException {
        if (anterior.isBlank()) {
            ctx.log("  [X] No hay release anterior a la cual volver.");
            return false;
        }
        CommandResult r = ctx.ejecutar(
                "ln -sfn " + releases + "/" + anterior + " " + current
                        + " && readlink -f " + current, 60);
        boolean ok = r.primeraLinea().trim().equals(releases + "/" + anterior);
        if (ok) {
            ctx.ok("ROLLBACK: current → releases/" + corto(anterior));
            NginxSyncHelper.recargarServiciosPostConmutacion(ctx);
        } else {
            ctx.log("  [X] El rollback no apuntó donde se esperaba: " + r.salida().trim());
        }
        return ok;
    }

    /** Conserva las releases más recientes sin tocar la activa. */
    private void limpiarReleases(DeploymentContext ctx, String releases, String activa) throws StageException {
        CommandResult lista = ctx.ejecutar(
                "ls -1t " + releases + " 2>/dev/null", 60);
        java.util.List<String> todas = new java.util.ArrayList<>();
        for (String l : lista.salida().split("\n")) {
            String n = l.trim();
            if (!n.isBlank()) {
                todas.add(n);
            }
        }
        int conservadas = 0;
        for (String nombre : todas) {
            conservadas++;
            if (conservadas <= 3 || (releases + "/" + nombre).equals(activa)) {
                continue;
            }
            if (!CommandValidator.esSha(nombre)) {
                continue; // no borrar nombres desconocidos
            }
            ctx.ejecutar("rm -rf " + releases + "/" + nombre, 300);
        }
        int borradas = Math.max(0, todas.size() - 3);
        if (borradas > 0) {
            ctx.log("  Releases antiguas eliminadas: " + borradas + " (se conservan 3).");
        }
    }

    // ------------------------------------------------------------------

    private static String corto(String sha) {
        return sha == null || sha.isBlank() ? "(desconocido)"
                : sha.substring(0, Math.min(8, sha.length()));
    }

    private static String recortar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
