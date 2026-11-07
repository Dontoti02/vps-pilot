package com.bolsadeploy.updates;

import com.bolsadeploy.backups.BackupService;
import com.bolsadeploy.deployment.DependenciasAppStage;
import com.bolsadeploy.deployment.DeploymentContext;
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
        String current = ctx.rutaReleaseActual();
        String releases = ctx.rutaBase() + "/releases";

        // -------------------------------------------------- Release anterior
        String anterior = releaseActual(ctx);
        ctx.valores.put("releaseAnterior", anterior);
        ctx.valores.put("commitAnterior", anterior);
        if (objetivo.equalsIgnoreCase(anterior)) {
            return new Resultado(true, "Ya está desplegado el commit " + corto(objetivo)
                    + ": no hay nada que actualizar.", anterior, anterior, false, "");
        }

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
            ctx.exigirExito("rm -rf " + nueva, 120, "Limpieza de release previa incompleta");
            clonarEn(ctx, objetivo, nueva);
            prepararEnlaces(ctx, nueva);

            // Dependencias PHP y frontend (reutiliza la etapa ya probada)
            ctx.valores.put("releasePath", nueva);
            ctx.valores.put("releaseSha", objetivo);
            new DependenciasAppStage().ejecutar(ctx);

            // Migraciones + cachés + permisos sobre la release NUEVA (current
            // aún apunta a la anterior: la web sigue funcionando)
            new MigracionStage().ejecutar(ctx);

        } catch (StageException e) {
            ctx.ejecutar("rm -rf " + nueva, 120); // no dejar releases a medias
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
        CommandResult enlace = ctx.ejecutar(
                "ln -sfn " + nueva + " " + current + " && readlink -f " + current, 60);
        String trasEnlace = enlace.primeraLinea().trim();
        if (!trasEnlace.equals(nueva)) {
            // Restaurar el enlace anterior si se hubiera perdido
            if (!anterior.isBlank()) {
                ctx.ejecutar("ln -sfn " + releases + "/" + anterior + " " + current, 60);
            }
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "La conmutación de 'current' no quedó donde se esperaba (leído: '"
                            + trasEnlace + "'). Se restauró la versión anterior.");
        }
        ctx.ok("current → releases/" + corto(objetivo) + " (conmutación atómica)");

        // ------------------------------------------------------ Validación
        String fallo = validarHttp(ctx);
        if (fallo != null) {
            ctx.log("  [X] La versión nueva no pasa la validación: " + fallo);
            boolean rollbackOk = retroceder(ctx, releases, current, anterior);
            if (rollbackOk) {
                String falloTras = validarHttp(ctx);
                if (falloTras != null) {
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
                        + " migración(es) pendiente(s) aplicadas)."
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
        String repo = UpdatePlanner.normalizarRepo(ctx.ajustes.getRepoGitHub());
        String url = "https://github.com/" + repo + ".git";
        String rama = ctx.institucion.getRama() != null && !ctx.institucion.getRama().isBlank()
                ? ctx.institucion.getRama() : ctx.ajustes.getRamaDefault();
        CommandValidator.exigirGitRef(rama);
        String tmp = "/tmp/bolsa-upd-" + ctx.institucion.getSlug();

        ctx.log("  Clonando " + repo + " (rama " + rama + ")…");
        ctx.exigirExito("rm -rf " + tmp + " && git clone --depth 50 --branch "
                + CommandValidator.escaparShell(rama) + " " + CommandValidator.escaparShell(url)
                + " " + tmp, 900, "git clone");

        CommandResult sel = ctx.ejecutar(
                "cd " + tmp + " && { git cat-file -e " + sha + "^{commit} 2>/dev/null"
                        + " || git fetch --depth 1 origin " + sha + "; }"
                        + " && git checkout --detach " + sha + " && git rev-parse HEAD", 600);
        if (!sel.exito()) {
            ctx.ejecutar("rm -rf " + tmp, 60);
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo obtener el commit " + corto(sha) + ": " + recortar(sel.salida()));
        }
        String obtenido = sel.salida().trim().lines().reduce((a, b) -> b).orElse("");
        if (!obtenido.equalsIgnoreCase(sha)) {
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

    /** Enlaces persistentes y propietario (mismas reglas que la instalación). */
    private void prepararEnlaces(DeploymentContext ctx, String release) throws StageException {
        String shared = ctx.rutaShared();
        ctx.ejecutar("rm -rf " + release + "/public/uploads", 30);
        ctx.exigirExito("rm -f " + release + "/.env && ln -sfn " + shared + "/.env " + release + "/.env",
                30, "Enlace .env → shared");
        ctx.exigirExito("rm -rf " + release + "/storage && ln -sfn " + shared + "/storage "
                + release + "/storage", 30, "Enlace storage → shared");
        ctx.exigirExito("rm -rf " + release + "/public/uploads && ln -sfn " + shared
                + "/public/uploads " + release + "/public/uploads", 30, "Enlace uploads → shared");
        ctx.exigirExito("mkdir -p " + release + "/bootstrap/cache", 30, "bootstrap/cache");
        ctx.exigirExito(ctx.sudo("chown -R www-data:www-data " + release), 300,
                "Propietario www-data de la release");
    }

    /** Validación real por HTTP contra 127.0.0.1 con Host del dominio. */
    private String validarHttp(DeploymentContext ctx) {
        String dominio = ctx.institucion.dominioPrincipal();
        String tmp = "/tmp/bolsa-upd-val-" + ctx.institucion.getSlug() + ".html";
        try {
            CommandResult r = ctx.ejecutar(
                    "curl -s -m 25 -H 'Host: " + dominio + "' -o " + tmp
                            + " -w '%{http_code}' http://127.0.0.1/", 90);
            String codigo = r.primeraLinea().trim();
            if (!"200".equals(codigo)) {
                return "GET / responde HTTP " + (codigo.isBlank() ? "(sin respuesta)" : codigo);
            }
            CommandResult c = ctx.ejecutar(
                    "grep -c 'Oportunidades Laborales' " + tmp + "; "
                            + "grep -cE 'Whoops|SQLSTATE|Fatal error' " + tmp + "; "
                            + "wc -c < " + tmp, 60);
            String[] d = c.salida().trim().split("\\s+");
            long ancla = d.length > 0 ? aEntero(d[0]) : 0;
            long rotos = d.length > 1 ? aEntero(d[1]) : 0;
            long bytes = d.length > 2 ? aEntero(d[2]) : 0;
            if (bytes < 500) {
                return "la página tiene solo " + bytes + " bytes (render incompleto)";
            }
            if (ancla < 1) {
                return "el HTML no contiene el título real (¿otro sitio o caché obsoleta?)";
            }
            if (rotos > 0) {
                return "la página muestra errores de la aplicación (Whoops/SQLSTATE/Fatal error)";
            }
            return null;
        } catch (StageException e) {
            return "no se pudo comprobar el sitio: " + e.getMessage();
        } finally {
            try {
                ctx.ejecutar("rm -f " + tmp, 30);
            } catch (StageException ignored) {
            }
        }
    }

    /** Vuelve a apuntar current a la release anterior y verifica. */
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
            ctx.ejecutar("cd " + releases + "/" + anterior
                    + " && sudo -u www-data php artisan config:clear >/dev/null 2>&1; "
                    + "cd " + releases + "/" + anterior
                    + " && sudo -u www-data php artisan config:cache >/dev/null 2>&1", 300);
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

    private static long aEntero(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        String limpio = s.replaceAll("[^0-9]", "");
        if (limpio.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(limpio);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String recortar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
