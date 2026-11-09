package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.security.CommandValidator;

/**
 * Etapa 5 — Código fuente (release + enlaces persistentes).
 *
 * Layout por institución:
 * <pre>
 * /var/www/bolsadeploy/&lt;slug&gt;/
 *   ├── releases/&lt;sha&gt;      código en esa versión (sin .git)
 *   ├── shared/              .env, storage/, public/uploads/  ← NUNCA se tocan
 *   ├── backups/
 *   └── current -&gt; releases/&lt;sha&gt;
 * </pre>
 *
 * Puntos críticos:
 * <ul>
 *   <li>Se elimina {@code public/uploads} de la release porque el repositorio
 *       tiene CVs reales versionados: los archivos subidos viven SOLO en
 *       {@code shared/public/uploads} (enlace simbólico).</li>
 *   <li>{@code .env} y {@code storage} son enlaces a {@code shared}: una
 *       actualización nunca pisa la configuración ni los archivos persistentes.</li>
 *   <li>El enlace {@code current} solo se crea en instalaciones nuevas; en
 *       actualizaciones lo gestiona el flujo de actualización de forma atómica.</li>
 * </ul>
 */
public class CodigoStage implements DeploymentStage {

    @Override
    public String id() {
        return "codigo";
    }

    @Override
    public String nombre() {
        return "Código fuente y enlaces persistentes";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        try {
            CommandResult r = ctx.ejecutar(
                    "test -e " + ctx.rutaReleaseActual() + " && echo SI", 30);
            return "SI".equals(r.primeraLinea().trim());
        } catch (StageException e) {
            return false;
        }
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String base = ctx.rutaBase();
        String shared = ctx.rutaShared();
        String current = ctx.rutaReleaseActual();
        String releases = base + "/releases";
        String slug = ctx.institucion.getSlug();

        // -------------------------------------------------- Estructura base
        ctx.exigirExito("mkdir -p " + releases
                + " " + shared
                + " " + shared + "/storage/logs"
                + " " + shared + "/storage/framework/cache"
                + " " + shared + "/storage/framework/data"
                + " " + shared + "/storage/framework/sessions"
                + " " + shared + "/storage/framework/views"
                + " " + shared + "/storage/app/public"
                + " " + shared + "/public/uploads"
                + " " + base + "/backups", 60, "Creación del árbol de directorios");

        // ------------------------------------------------------ Release actual
        String releasePath = "";
        CommandResult actual = ctx.ejecutar("readlink -f " + current + " 2>/dev/null || true", 30);
        String leido = actual.primeraLinea().trim();
        if (!leido.isBlank() && !".".equals(leido)) {
            CommandResult existe = ctx.ejecutar("test -d " + leido + " && echo SI", 30);
            if ("SI".equals(existe.primeraLinea().trim())) {
                releasePath = leido;
            }
        }
        if (releasePath.isBlank()) {
            CommandResult lista = ctx.ejecutar("ls -1 " + releases + " 2>/dev/null | head -1", 30);
            String nombre = lista.primeraLinea().trim();
            if (!nombre.isBlank()) {
                releasePath = releases + "/" + nombre;
            }
        }

        // ---------------------------------------------------------- Clonar
        if (releasePath.isBlank()) {
            releasePath = clonar(ctx, releases, slug);
        } else {
            ctx.log("  Release existente: " + releasePath + " (no se vuelve a clonar)");
        }

        String sha = releasePath.substring(releasePath.lastIndexOf('/') + 1);
        if (!CommandValidator.esSha(sha)) {
            ctx.log("  [!] El nombre de release no es un SHA (" + sha + "); se usará tal cual.");
        }
        ctx.valores.put("releasePath", releasePath);
        ctx.valores.put("releaseSha", sha);

        // ------------------------------------------------- Enlaces persistentes adaptables
        ctx.ejecutar("test -f " + shared + "/.env || touch " + shared + "/.env", 30);
        ctx.exigirExito("rm -f " + releasePath + "/.env && ln -sfn " + shared + "/.env "
                + releasePath + "/.env", 30, "Enlace .env → shared");

        // Enlace de almacenamiento si el proyecto tiene carpeta storage
        CommandResult tieneStorage = ctx.ejecutar("test -d " + releasePath + "/storage && echo SI", 30);
        if ("SI".equals(tieneStorage.primeraLinea().trim())) {
            ctx.exigirExito("rm -rf " + releasePath + "/storage && ln -sfn " + shared + "/storage "
                    + releasePath + "/storage", 30, "Enlace storage → shared");
        }

        // Enlace de uploads si el proyecto tiene public/uploads
        CommandResult tieneUploads = ctx.ejecutar("test -d " + releasePath + "/public/uploads && echo SI", 30);
        if ("SI".equals(tieneUploads.primeraLinea().trim())) {
            ctx.ejecutar("rm -rf " + releasePath + "/public/uploads", 30);
            ctx.exigirExito("ln -sfn " + shared + "/uploads " + releasePath + "/public/uploads", 30,
                    "Enlace public/uploads → shared");
        }

        // bootstrap/cache si el proyecto lo contempla
        ctx.ejecutar("test -d " + releasePath + "/bootstrap && mkdir -p " + releasePath + "/bootstrap/cache", 30);

        // Document root para Nginx: si el repositorio no tiene carpeta public/, enlazar dist, build o la raíz
        CommandResult tienePublic = ctx.ejecutar("test -d " + releasePath + "/public && echo SI", 30);
        if (!"SI".equals(tienePublic.primeraLinea().trim())) {
            CommandResult tieneDist = ctx.ejecutar("test -d " + releasePath + "/dist && echo SI", 30);
            CommandResult tieneBuild = ctx.ejecutar("test -d " + releasePath + "/build && echo SI", 30);
            if ("SI".equals(tieneDist.primeraLinea().trim())) {
                ctx.ejecutar("ln -sfn dist " + releasePath + "/public", 30);
                ctx.log("  [i] Detectada carpeta 'dist'; configurada como raíz pública para Nginx.");
            } else if ("SI".equals(tieneBuild.primeraLinea().trim())) {
                ctx.ejecutar("ln -sfn build " + releasePath + "/public", 30);
                ctx.log("  [i] Detectada carpeta 'build'; configurada como raíz pública para Nginx.");
            } else {
                ctx.ejecutar("ln -sfn . " + releasePath + "/public", 30);
                ctx.log("  [i] Repositorio sin subcarpeta pública; sirviendo raíz para Nginx.");
            }
        }

        // ------------------------------------------------------ Propietario
        ctx.exigirExito(ctx.sudo("chown -R www-data:www-data " + releasePath), 300,
                "Propietario www-data de la release");

        // ----------------------------------------------------- Enlace current
        CommandResult hayCurrent = ctx.ejecutar("test -e " + current + " && echo SI", 30);
        if (!"SI".equals(hayCurrent.primeraLinea().trim())) {
            ctx.exigirExito("ln -sfn " + releasePath + " " + current, 30,
                    "Creación del enlace current");
            ctx.ok("current → " + releasePath);
        } else {
            ctx.log("  'current' ya existe; el cambio de versión lo gestiona la actualización.");
        }

        // ------------------------------------------------------- Verificación
        CommandResult verif = ctx.ejecutar("test -d " + current + " && ls -A " + current + " 2>/dev/null | head -1", 30);
        if (!verif.exito() || verif.salida().isBlank()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "La release no se desplegó correctamente en " + current);
        }
        ctx.ok("Estructura de release " + sha + " verificada con éxito");
    }

    // ------------------------------------------------------------------

    /** Clona el repositorio en una release nombrada por su SHA. */
    private String clonar(DeploymentContext ctx, String releases, String slug) throws StageException {
        String repo = (ctx.repoGitObjetivo != null && !ctx.repoGitObjetivo.isBlank())
                ? ctx.repoGitObjetivo
                : ctx.institucion.repositorioEfectivo(ctx.ajustes);
        String url = CommandValidator.resolverUrlGit(repo, ctx.ajustes.getGithubToken());
        String rama = (ctx.ramaObjetivo != null && !ctx.ramaObjetivo.isBlank())
                ? ctx.ramaObjetivo
                : ctx.institucion.getRama();
        if (rama == null || rama.isBlank()) {
            rama = ctx.ajustes.getRamaDefault();
        }
        CommandValidator.exigirGitRef(rama);

        String tmp = "/tmp/vpspilot-clone-" + slug;
        String objetivo = ctx.valores.getOrDefault("commitObjetivo", "").trim();

        ctx.log("  Clonando " + repo + " (rama " + rama + ")…");
        ctx.exigirExito("rm -rf " + tmp + " && git clone --depth 50 --branch "
                + CommandValidator.escaparShell(rama) + " " + CommandValidator.escaparShell(url)
                + " " + tmp, 900, "git clone");

        String sha;
        if (!objetivo.isBlank()) {
            if (!CommandValidator.esSha(objetivo)) {
                throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                        "El commit objetivo no es un SHA válido: " + objetivo);
            }
            ctx.log("  Seleccionando el commit " + objetivo.substring(0, Math.min(10, objetivo.length())) + "…");
            CommandResult sel = ctx.ejecutar(
                    "cd " + tmp + " && { git cat-file -e " + objetivo + "^{commit} 2>/dev/null"
                            + " || git fetch --depth 1 origin " + objetivo + "; }"
                            + " && git checkout --detach " + objetivo, 600);
            if (!sel.exito()) {
                ctx.ejecutar("rm -rf " + tmp, 60);
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "No se pudo obtener el commit " + objetivo + ": " + limpiar(sel.salida()));
            }
        }

        CommandResult rev = ctx.ejecutar("cd " + tmp + " && git rev-parse HEAD", 60);
        sha = rev.primeraLinea().trim();
        if (!CommandValidator.esSha(sha)) {
            ctx.ejecutar("rm -rf " + tmp, 60);
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo determinar el SHA clonado: " + limpiar(rev.salida()));
        }

        String destino = releases + "/" + sha;
        CommandResult mover = ctx.ejecutar(
                "rm -rf " + tmp + "/.git && mkdir -p " + releases + " && mv " + tmp + " " + destino, 300);
        if (!mover.exito()) {
            ctx.ejecutar("rm -rf " + tmp, 60);
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo mover la release a " + destino + ": " + limpiar(mover.salida()));
        }
        ctx.ok("Código clonado en releases/" + sha.substring(0, Math.min(10, sha.length())));
        return destino;
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
