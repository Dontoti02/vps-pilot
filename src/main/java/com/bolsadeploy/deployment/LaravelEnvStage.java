package com.bolsadeploy.deployment;

import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.security.CommandValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Etapa 6 — Archivo {@code .env} de Laravel.
 *
 * Reglas inamovibles del orquestador:
 * <ul>
 *   <li><b>Nunca se sobrescribe un .env existente</b>: si ya está en
 *       {@code shared/}, se conserva tal cual y solo se comprueba que tenga
 *       APP_KEY.</li>
 *   <li>En instalación nueva se genera a partir de {@code .env.example} del
 *       propio repo con {@link EnvFileBuilder} (se conservan comentarios y
 *       claves no tocadas).</li>
 *   <li>{@code APP_KEY} solo se genera en instalación NUEVA
 *       ({@code php artisan key:generate --force}); en actualizaciones jamás.</li>
 *   <li>Modo producción: {@code APP_ENV=production}, {@code APP_DEBUG=false},
 *       drivers de sesión/cola/caché en base de datos.</li>
 * </ul>
 */
public class LaravelEnvStage implements DeploymentStage {

    @Override
    public String id() {
        return "laravel-env";
    }

    @Override
    public String nombre() {
        return "Archivo .env de Laravel";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        try {
            CommandResult r = ctx.ejecutar(
                    "test -f " + ctx.rutaShared() + "/.env"
                            + " && grep -q '^APP_KEY=base64:' " + ctx.rutaShared() + "/.env"
                            + " && echo SI", 30);
            return "SI".equals(r.primeraLinea().trim());
        } catch (StageException e) {
            return false;
        }
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String sharedEnv = ctx.rutaShared() + "/.env";
        Institution inst = ctx.institucion;

        // ------------------------------------------ ¿Ya existe un .env válido?
        CommandResult existe = ctx.ejecutar("test -f " + sharedEnv + " && echo SI", 30);
        boolean existia = "SI".equals(existe.primeraLinea().trim());

        if (existia) {
            ctx.log("  .env existente en shared/ → NO se modifica (regla del orquestador).");
            ctx.valores.put("envCreado", "false");
        } else {
            // --------------------------------------------------- Generarlo
            String release = ctx.valores.get("releasePath");
            if (release == null || release.isBlank()) {
                throw new StageException(StageException.Tipo.FATAL,
                        "No se conoce la ruta de la release.");
            }

            CommandResult tieneExample = ctx.ejecutar("test -f " + release + "/.env.example && echo SI", 30);
            CommandResult tieneEnvEnRepo = ctx.ejecutar("test -f " + release + "/.env && echo SI", 30);
            String url = inst.urlBase();
            String contenido;

            if ("SI".equals(tieneExample.primeraLinea().trim())) {
                CommandResult ejemplo = ctx.ejecutar("cat " + release + "/.env.example", 60);
                EnvFileBuilder b = EnvFileBuilder.parse(ejemplo.stdout() != null ? ejemplo.stdout() : "");
                b.poner("APP_NAME", inst.getNombre());
                b.poner("APP_ENV", "production");
                b.poner("APP_DEBUG", "false");
                b.poner("APP_URL", url);
                if (inst.getNombreBaseDatos() != null && !inst.getNombreBaseDatos().isBlank()) {
                    b.poner("DB_CONNECTION", "mysql");
                    b.poner("DB_HOST", "127.0.0.1");
                    b.poner("DB_PORT", "3306");
                    b.poner("DB_DATABASE", inst.getNombreBaseDatos());
                    b.poner("DB_USERNAME", inst.getUsuarioBaseDatos());
                    b.poner("DB_PASSWORD", ctx.dbPassword);
                }
                contenido = b.aContenido();
            } else if ("SI".equals(tieneEnvEnRepo.primeraLinea().trim())) {
                CommandResult envRepo = ctx.ejecutar("cat " + release + "/.env", 60);
                contenido = envRepo.stdout() != null ? envRepo.stdout() : "";
            } else {
                // Generar un .env estándar limpio
                StringBuilder sb = new StringBuilder();
                sb.append("APP_NAME=").append(inst.getNombre()).append("\n");
                sb.append("APP_ENV=production\n");
                sb.append("APP_DEBUG=false\n");
                sb.append("APP_URL=").append(url).append("\n");
                if (inst.getNombreBaseDatos() != null && !inst.getNombreBaseDatos().isBlank()) {
                    sb.append("DB_CONNECTION=mysql\n");
                    sb.append("DB_HOST=127.0.0.1\n");
                    sb.append("DB_PORT=3306\n");
                    sb.append("DB_DATABASE=").append(inst.getNombreBaseDatos()).append("\n");
                    sb.append("DB_USERNAME=").append(inst.getUsuarioBaseDatos()).append("\n");
                    sb.append("DB_PASSWORD=").append(ctx.dbPassword).append("\n");
                }
                contenido = sb.toString();
            }

            Path local = null;
            String tmp = "/tmp/vpspilot-" + inst.getSlug() + ".env";
            try {
                local = Files.createTempFile("vpspilot-env-", ".env");
                Files.writeString(local, contenido, StandardCharsets.UTF_8);
                ctx.ssh.subir(local, tmp);
                ctx.exigirExito(ctx.sudo("install -m 640 -o www-data -g www-data " + tmp + " " + sharedEnv),
                        60, "Instalación de .env en shared/");
            } catch (IOException e) {
                throw new StageException(StageException.Tipo.FATAL,
                        "No se pudo escribir el .env: " + e.getMessage(), e);
            } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "Fallo de conexión al subir el .env: " + e.getMessage(), e);
            } finally {
                if (local != null) {
                    try {
                        Files.deleteIfExists(local);
                    } catch (IOException ignored) {
                    }
                }
                ctx.ejecutar("rm -f " + tmp, 30);
            }
            ctx.valores.put("envCreado", "true");
            ctx.ok(".env configurado en shared/.env");
        }

        // ------------------------------------------------------- APP_KEY
        String release = ctx.valores.get("releasePath");
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + release + "/artisan && echo SI", 30);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            CommandResult tieneClave = ctx.ejecutar(
                    "grep -q '^APP_KEY=base64:' " + sharedEnv + " && echo SI", 30);
            boolean conClave = "SI".equals(tieneClave.primeraLinea().trim());
            if (!conClave && !ctx.esActualizacion) {
                ctx.log("  Generando APP_KEY de Laravel…");
                CommandResult gen = ctx.ejecutar(
                        "cd " + release + " && sudo -u www-data php artisan key:generate --force", 120);
                if (gen.exito()) {
                    ctx.ok("APP_KEY generada y verificada");
                } else {
                    ctx.log("  [!] Aviso: php artisan key:generate no terminó con éxito: " + limpiar(gen.salida()));
                }
            } else {
                ctx.log("  APP_KEY conservada o no requerida.");
            }
        } else {
            ctx.log("  [i] Repositorio sin artisan; se omite key:generate.");
        }

        // ------------------------------------------- Verificación de claves clave
        CommandResult contenido = ctx.ejecutar("cat " + sharedEnv, 60);
        if (!contenido.exito()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo releer .env para verificarlo: " + limpiar(contenido.salida()));
        }

        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            CommandResult debug = ctx.ejecutar("grep -E '^APP_DEBUG=' " + sharedEnv, 30);
            if (debug.exito() && !debug.stdout().contains("APP_DEBUG=false")) {
                ctx.log("  [!] Aviso: APP_DEBUG no está explícitamente en false en .env.");
            }
        }
        ctx.ok(".env verificado y listo en shared/.env");
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
