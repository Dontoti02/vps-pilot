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
        CommandResult tieneComposer = ctx.ejecutar("test -f " + release + "/composer.json && echo SI", 30);
        if ("SI".equals(tieneComposer.primeraLinea().trim())) {
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
        } else {
            ctx.log("  [i] No se detectó composer.json; se omite Composer.");
        }

        // --------------------------------------------------------------- npm
        CommandResult tienePackage = ctx.ejecutar("test -f " + release + "/package.json && echo SI", 30);
        if ("SI".equals(tienePackage.primeraLinea().trim())) {
            ctx.log("  npm install / ci (como www-data)…");
            CommandResult ci = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data env npm_config_cache=/tmp/npm-cache"
                            + " npm ci --no-audit --no-fund 2>/dev/null || "
                            + "sudo -u www-data env npm_config_cache=/tmp/npm-cache npm install --no-audit --no-fund", 900);
            if (!ci.exito()) {
                ctx.log("  [!] Aviso: npm install terminó con advertencias: " + limpiar(ci.salida()));
            }

            // Comprobar si existe script de build
            CommandResult tieneBuild = ctx.ejecutar(
                    "grep -E '\"build\"\\s*:' " + release + "/package.json 2>/dev/null && echo SI", 30);
            if ("SI".equals(tieneBuild.primeraLinea().trim())) {
                ctx.log("  npm run build…");
                CommandResult build = ctx.ejecutar(
                        "cd " + release + " && sudo -u www-data env npm_config_cache=/tmp/npm-cache"
                                + " npm run build", 900);
                if (!build.exito()) {
                    ctx.log("  [!] Advertencia: npm run build falló: " + limpiar(build.salida()));
                } else {
                    ctx.ok("Compilación de frontend (build) finalizada");
                }
            } else {
                ctx.log("  [i] package.json no contiene script 'build'; se omite compilación.");
            }
        } else {
            ctx.log("  [i] No se detectó package.json; se omite npm.");
        }

        // ------------------------------------------------------ Verificación
        if ("SI".equals(tieneComposer.primeraLinea().trim())) {
            CommandResult v = ctx.ejecutar("test -f " + release + "/vendor/autoload.php && echo OK", 30);
            if (!"OK".equals(v.primeraLinea().trim())) {
                ctx.log("  [!] Aviso: vendor/autoload.php no fue encontrado.");
            }
        }
        ctx.ok("Etapa de dependencias finalizada exitosamente");
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 700 ? l.substring(0, 700) + "…" : l;
    }
}
