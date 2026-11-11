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

        // ---------------------------------------------------------- Comandos Artisan (si aplica)
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + release + "/artisan && echo SI", 30);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            // Migraciones
            ctx.log("  php artisan migrate --force…");
            CommandResult mig = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan migrate --force --no-interaction", 600);
            if (!mig.exito()) {
                ctx.log("  [!] Aviso: php artisan migrate terminó con: " + limpiar(mig.salida()));
            } else {
                String migSalida = mig.salida().replaceAll("\\s+", " ").trim();
                ctx.ok("Migraciones: " + recortar(migSalida, 300));
            }

            // storage:link
            CommandResult tieneStorage = ctx.ejecutar("test -d " + release + "/storage && echo SI", 30);
            if ("SI".equals(tieneStorage.primeraLinea().trim())) {
                CommandResult link = ctx.ejecutar(
                        "cd " + release + " && sudo -u www-data php artisan storage:link --force", 120);
                if (link.exito()) {
                    ctx.ok("Enlace public/storage asegurado");
                }
            }

            // Cachés
            CommandResult cfgCache = ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan config:cache", 300);
            if (!cfgCache.exito()) {
                ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan config:clear", 60);
            }

            CommandResult rutas = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan route:cache", 300);
            if (rutas.exito()) {
                ctx.ok("Rutas cacheadas");
            } else {
                ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan route:clear", 60);
            }

            CommandResult vistas = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan view:cache", 300);
            if (vistas.exito()) {
                ctx.ok("Vistas compiladas");
            } else {
                ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan view:clear", 60);
            }
        } else {
            ctx.log("  [i] Repositorio sin artisan; se omiten migraciones y cachés de Laravel.");
        }

        // ----------------------------------------------------------- Permisos
        ctx.log("  Aplicando permisos seguros…");
        ctx.exigirExito("chmod -R u=rwX,g=rX,o=rX " + release, 600, "Permisos base de la release");
        ctx.ejecutar("test -d " + release + "/bootstrap/cache && chown -R www-data:www-data "
                + release + "/bootstrap/cache && chmod -R 775 " + release + "/bootstrap/cache", 120);
        ctx.ejecutar("test -d " + shared + "/storage && chown -R www-data:www-data "
                + shared + "/storage && chmod -R 775 " + shared + "/storage", 120);
        ctx.ejecutar("test -d " + shared + "/public/uploads && chown -R www-data:www-data "
                + shared + "/public/uploads && chmod -R 775 " + shared + "/public/uploads", 120);
        ctx.ejecutar("test -f " + shared + "/.env && chown www-data:www-data "
                + shared + "/.env && chmod 640 " + shared + "/.env", 60);

        // Eliminar cualquier 777 residual y verificar que no quede ninguno
        ctx.ejecutar("find " + release + " " + shared + " -perm 0777 -not -type l "
                + "-exec chmod 755 {} + 2>/dev/null", 300);
        CommandResult setenta = ctx.ejecutar(
                "find " + release + " " + shared + " -perm 0777 -not -type l 2>/dev/null | head -5", 60);
        if (!setenta.salida().isBlank()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Quedaron archivos/directorios con permiso 777: " + limpiar(setenta.salida()));
        }
        ctx.ok("Permisos verificados: sin 777");

        // ------------------------------------------------------ Verificación final
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            CommandResult status = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan migrate:status", 300);
            if (!status.exito()) {
                ctx.log("  [!] Aviso: php artisan migrate:status: " + limpiar(status.salida()));
            } else {
                ctx.ok("migrate:status correcto (esquema consistente)");
            }
        }
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
