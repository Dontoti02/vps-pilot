package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;

/**
 * Etapa 8 — Migraciones, caché de Laravel y permisos.
 *
 * <ul>
 *   <li>{@code php artisan migrate --force}: en instalación nueva es un no-op
 *       (el esquema vino de {@code base_instalacion.sql} con las migraciones ya
 *       registradas); en actualizaciones aplica SOLO las migraciones nuevas.</li>
 *   <li>{@code storage:link}, {@code config:cache}, {@code route:cache} y
 *       {@code view:cache} (los dos últimos con aviso si el repo usa rutas con
 *       closures, sin fingir éxito).</li>
 *   <li>Permisos: 644/755 en la release, 775 en storage y bootstrap/cache,
 *       640 en .env y <b>ningún 777</b> (se detectan y corrigen).</li>
 * </ul>
 *
 * Nunca se ejecuta {@code migrate:fresh} ni {@code db:wipe}.
 */
public class MigracionStage implements DeploymentStage {

    @Override
    public String id() {
        return "migracion";
    }

    @Override
    public String nombre() {
        return "Migraciones, caché y permisos";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        return false; // siempre se ejecuta: es idempotente y garantiza permisos
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String release = ctx.valores.get("releasePath");
        String shared = ctx.rutaShared();
        if (release == null || release.isBlank()) {
            throw new StageException(StageException.Tipo.FATAL, "No se conoce la ruta de la release.");
        }

        // ---------------------------------------------------------- Migraciones
        ctx.log("  php artisan migrate --force…");
        CommandResult mig = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data php artisan migrate --force --no-interaction", 600);
        if (!mig.exito()) {
            throw new StageException(StageException.Tipo.REQUIERE_APROBACION,
                    "php artisan migrate falló (exit " + mig.exitCode() + "): " + limpiar(mig.salida())
                            + ". NO se aplicó ningún cambio posterior; en una actualización esto "
                            + "dispara el rollback automático de la release.");
        }
        String migSalida = mig.salida().replaceAll("\\s+", " ").trim();
        if (migSalida.contains("Nothing to migrate") || migSalida.contains("No pending")) {
            ctx.log("  Sin migraciones pendientes (esquema ya al día).");
        } else {
            ctx.ok("Migraciones aplicadas: " + recortar(migSalida, 300));
        }

        // ------------------------------------------------------- storage:link
        CommandResult link = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data php artisan storage:link --force", 120);
        CommandResult linkOk = ctx.ejecutar("test -e " + release + "/public/storage && echo SI", 30);
        if (!"SI".equals(linkOk.primeraLinea().trim())) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se creó public/storage: " + limpiar(link.salida()));
        }
        ctx.ok("Enlace public/storage creado");

        // ------------------------------------------------------------ Cachés
        ctx.exigirExito("cd " + release + " && sudo -u www-data php artisan config:cache", 300,
                "php artisan config:cache");

        CommandResult rutas = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data php artisan route:cache", 300);
        if (rutas.exito()) {
            ctx.ok("Rutas cacheadas");
        } else {
            ctx.log("  [!] route:cache no disponible (probablemente hay rutas con closures): "
                    + limpiar(rutas.salida()));
            ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan route:clear", 60);
            ctx.valores.put("routeCache", "omitido");
        }

        CommandResult vistas = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data php artisan view:cache", 300);
        if (vistas.exito()) {
            ctx.ok("Vistas compiladas");
        } else {
            ctx.log("  [!] view:cache falló: " + limpiar(vistas.salida()));
            ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan view:clear", 60);
            ctx.valores.put("viewCache", "omitido");
        }

        // ----------------------------------------------------------- Permisos
        ctx.log("  Aplicando permisos (sin 777)…");
        ctx.exigirExito("chmod -R u=rwX,g=rX,o=rX " + release, 600, "Permisos base de la release");
        ctx.exigirExito("chown -R www-data:www-data " + release + "/bootstrap/cache && "
                + "chmod -R 775 " + release + "/bootstrap/cache", 120,
                "Permisos de bootstrap/cache");
        ctx.exigirExito("chown -R www-data:www-data " + shared + "/storage " + shared
                + "/public/uploads && chmod -R 775 " + shared + "/storage " + shared
                + "/public/uploads", 300, "Permisos de storage y uploads compartidos");
        ctx.exigirExito("chown www-data:www-data " + shared + "/.env && chmod 640 " + shared + "/.env",
                60, "Permisos de .env (640)");

        // Eliminar cualquier 777 residual y verificar que no quede ninguno
        ctx.ejecutar("find " + release + " " + shared + " -perm 0777 -not -type l "
                + "-exec chmod 755 {} + 2>/dev/null", 300);
        CommandResult setenta = ctx.ejecutar(
                "find " + release + " " + shared + " -perm 0777 -not -type l 2>/dev/null | head -5", 60);
        if (!setenta.salida().isBlank()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Quedaron archivos/directorios con permiso 777: " + limpiar(setenta.salida()));
        }
        ctx.ok("Permisos verificados: sin 777, .env en 640, storage en 775");

        // ------------------------------------------------------ Verificación final
        CommandResult status = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data php artisan migrate:status", 300);
        if (!status.exito()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "php artisan migrate:status falló: " + limpiar(status.salida()));
        }
        ctx.ok("migrate:status correcto (esquema consistente)");
    }

    private static String recortar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 700 ? l.substring(0, 700) + "…" : l;
    }
}
