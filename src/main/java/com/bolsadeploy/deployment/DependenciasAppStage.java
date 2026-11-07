package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;

/**
 * Etapa 7 — Dependencias de la aplicación (PHP y frontend).
 *
 * Ejecuta en la release, <b>como www-data</b> (nunca como root, para que
 * {@code vendor/} y {@code node_modules/} no queden con propietario root):
 * <ol>
 *   <li>{@code composer install --no-dev --prefer-dist --no-interaction
 *       --optimize-autoloader}</li>
 *   <li>{@code npm ci} (con reserva a {@code npm install} si el lock está
 *       desincronizado, avisándolo en consola)</li>
 *   <li>{@code npm run build} (Vite + Tailwind)</li>
 *   <li>Verificación real: existe {@code vendor/autoload.php} y
 *       {@code public/build/manifest.json} (no se confía en el código de salida
 *       de npm, se comprueba el artefacto).</li>
 * </ol>
 */
public class DependenciasAppStage implements DeploymentStage {

    @Override
    public String id() {
        return "dependencias-app";
    }

    @Override
    public String nombre() {
        return "Dependencias de la aplicación (Composer + npm build)";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        try {
            String release = ctx.valores.get("releasePath");
            if (release == null || release.isBlank()) {
                return false;
            }
            CommandResult r = ctx.ejecutar(
                    "test -f " + release + "/vendor/autoload.php"
                            + " && test -f " + release + "/public/build/manifest.json"
                            + " && test -d " + release + "/node_modules && echo SI", 30);
            return "SI".equals(r.primeraLinea().trim());
        } catch (StageException e) {
            return false;
        }
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String release = ctx.valores.get("releasePath");
        if (release == null || release.isBlank()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se conoce la ruta de la release.");
        }

        // ------------------------------------------------------------ Composer
        ctx.log("  composer install (como www-data)…");
        CommandResult composer = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data env COMPOSER_HOME=/tmp/composer-home"
                        + " COMPOSER_MEMORY_LIMIT=-1 composer install --no-dev --prefer-dist"
                        + " --no-interaction --optimize-autoloader --no-progress", 900);
        if (!composer.exito()) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "composer install falló (exit " + composer.exitCode() + "): "
                            + limpiar(composer.salida())
                            + ". Revise las extensiones PHP y la conectividad con packagist.");
        }
        ctx.ok("Dependencias PHP instaladas (vendor/)");

        // --------------------------------------------------------------- npm
        ctx.log("  npm ci (como www-data)…");
        CommandResult ci = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data env npm_config_cache=/tmp/npm-cache"
                        + " npm ci --no-audit --no-fund", 900);
        if (!ci.exito()) {
            ctx.log("  [!] npm ci falló (" + limpiar(ci.salida())
                    + "). Se intenta npm install (package-lock desincronizado).");
            CommandResult install = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data env npm_config_cache=/tmp/npm-cache"
                            + " npm install --no-audit --no-fund", 900);
            if (!install.exito()) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "npm install también falló: " + limpiar(install.salida()));
            }
        }

        ctx.log("  npm run build (Vite)…");
        CommandResult build = ctx.ejecutar(
                "cd " + release + " && sudo -u www-data env npm_config_cache=/tmp/npm-cache"
                        + " npm run build", 900);
        if (!build.exito()) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "npm run build falló (exit " + build.exitCode() + "): " + limpiar(build.salida()));
        }

        // ------------------------------------------------------ Verificación
        CommandResult verif = ctx.ejecutar(
                "test -f " + release + "/vendor/autoload.php && echo VENDOR_OK; "
                        + "test -f " + release + "/public/build/manifest.json && echo MANIFEST_OK; "
                        + "ls " + release + "/public/build/ 2>/dev/null | head -5", 60);
        String salida = verif.salida();
        if (!salida.contains("VENDOR_OK")) {
            throw new StageException(StageException.Tipo.FATAL,
                    "vendor/autoload.php no existe tras composer install.");
        }
        if (!salida.contains("MANIFEST_OK")) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se generó public/build/manifest.json: la compilación de frontend no produjo "
                            + "artefactos. Salida de verificación: " + limpiar(salida));
        }
        ctx.valores.put("manifestOk", "true");
        ctx.ok("Artefactos verificados: vendor/autoload.php + public/build/manifest.json");
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 700 ? l.substring(0, 700) + "…" : l;
    }
}
