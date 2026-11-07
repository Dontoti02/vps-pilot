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
                        "No se conoce la ruta de la release para leer .env.example.");
            }
            CommandResult ejemplo = ctx.ejecutar("cat " + release + "/.env.example", 60);
            if (!ejemplo.exito() || ejemplo.stdout() == null || ejemplo.stdout().isBlank()) {
                throw new StageException(StageException.Tipo.FATAL,
                        "No se pudo leer .env.example de la release: " + limpiar(ejemplo.salida()));
            }

            EnvFileBuilder b = EnvFileBuilder.parse(ejemplo.stdout());
            String url = inst.urlBase();

            // Entorno de producción
            b.poner("APP_NAME", inst.getNombre());
            b.poner("APP_ENV", "production");
            b.poner("APP_DEBUG", "false");
            b.poner("APP_URL", url);
            b.poner("LOG_CHANNEL", "stack");
            b.poner("LOG_LEVEL", "warning");

            // Base de datos (usuario dedicado, nunca root)
            b.poner("DB_CONNECTION", "mysql");
            b.poner("DB_HOST", "127.0.0.1");
            b.poner("DB_PORT", "3306");
            b.poner("DB_DATABASE", inst.getNombreBaseDatos());
            b.poner("DB_USERNAME", inst.getUsuarioBaseDatos());
            b.poner("DB_PASSWORD", ctx.dbPassword);

            // Drivers sin Redis obligatorio (válido y persistente)
            b.poner("SESSION_DRIVER", "database");
            b.poner("QUEUE_CONNECTION", "database");
            b.poner("CACHE_STORE", "database");
            b.poner("BCRYPT_ROUNDS", "12");

            // Correo: SMTP solo si el usuario lo configuró
            if (inst.getSmtpHost() != null && !inst.getSmtpHost().isBlank()) {
                b.poner("MAIL_MAILER", "smtp");
                b.poner("MAIL_HOST", inst.getSmtpHost());
                b.poner("MAIL_PORT", String.valueOf(inst.getSmtpPuerto()));
                boolean ssl = inst.getSmtpPuerto() == 465;
                b.poner("MAIL_ENCRYPTION", ssl ? "ssl" : "tls");
                if (inst.getSmtpUsuario() != null && !inst.getSmtpUsuario().isBlank()) {
                    b.poner("MAIL_USERNAME", inst.getSmtpUsuario());
                    b.poner("MAIL_PASSWORD", ctx.smtpPassword);
                }
                String from = inst.getSmtpFrom() != null && !inst.getSmtpFrom().isBlank()
                        ? inst.getSmtpFrom()
                        : (inst.getEmailContacto() != null ? inst.getEmailContacto() : "");
                if (!from.isBlank()) {
                    b.poner("MAIL_FROM_ADDRESS", from);
                }
                b.poner("MAIL_FROM_NAME", inst.getNombre());
            } else {
                ctx.log("  [!] SMTP no configurado: el correo quedará en log (MAIL_MAILER=log).");
                ctx.valores.put("smtpOmitido", "true");
            }

            String contenido = b.aContenido();
            if (!contenido.contains("APP_KEY=")) {
                throw new StageException(StageException.Tipo.FATAL,
                        ".env.example no contiene APP_KEY; se aborta para no generar un archivo inválido.");
            }

            Path local = null;
            String tmp = "/tmp/bolsa-" + inst.getSlug() + ".env";
            try {
                local = Files.createTempFile("bolsa-env-", ".env");
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
            ctx.ok(".env generado en shared/.env (modo producción, APP_DEBUG=false)");
        }

        // ------------------------------------------------------- APP_KEY
        CommandResult tieneClave = ctx.ejecutar(
                "grep -q '^APP_KEY=base64:' " + sharedEnv + " && echo SI", 30);
        boolean conClave = "SI".equals(tieneClave.primeraLinea().trim());
        boolean esActualizacion = ctx.esActualizacion;

        if (conClave) {
            ctx.log("  APP_KEY presente; no se regenera (nunca en actualizaciones).");
        } else if (esActualizacion) {
            throw new StageException(StageException.Tipo.ESTADO_CONFLICTIVO,
                    "El .env existente no tiene APP_KEY y es una actualización: no se genera una clave "
                            + "nueva para no romper las sesiones/datos cifrados. Revise el .env manualmente.");
        } else {
            String release = ctx.valores.get("releasePath");
            ctx.log("  Generando APP_KEY (solo instalación nueva)…");
            CommandResult gen = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan key:generate --force", 120);
            if (!gen.exito()) {
                throw new StageException(StageException.Tipo.FATAL,
                        "php artisan key:generate falló: " + limpiar(gen.salida()));
            }
            CommandResult tras = ctx.ejecutar(
                    "grep -c '^APP_KEY=base64:' " + sharedEnv, 30);
            if (!tras.primeraLinea().trim().startsWith("1")) {
                throw new StageException(StageException.Tipo.FATAL,
                        "APP_KEY no quedó escrita en .env tras key:generate.");
            }
            ctx.ok("APP_KEY generada y verificada");
        }

        // ------------------------------------------- Verificación de claves clave
        String[] requeridas = {"APP_ENV", "APP_URL", "DB_CONNECTION", "DB_DATABASE",
                "DB_USERNAME", "SESSION_DRIVER", "QUEUE_CONNECTION", "CACHE_STORE"};
        CommandResult contenido = ctx.ejecutar("cat " + sharedEnv, 60);
        if (!contenido.exito()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo releer .env para verificarlo: " + limpiar(contenido.salida()));
        }
        StringBuilder faltantes = new StringBuilder();
        for (String clave : requeridas) {
            boolean ok = contenido.stdout().lines()
                    .anyMatch(l -> l.startsWith(clave + "=") && !l.isBlank());
            if (!ok) {
                faltantes.append(clave).append(' ');
            }
        }
        if (faltantes.length() > 0) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Faltan claves obligatorias en .env: " + faltantes.toString().trim());
        }

        CommandResult debug = ctx.ejecutar("grep -E '^APP_DEBUG=' " + sharedEnv, 30);
        if (!debug.stdout().contains("APP_DEBUG=false")) {
            throw new StageException(StageException.Tipo.FATAL,
                    "APP_DEBUG no está en false en .env (riesgo de fuga de datos en producción).");
        }
        ctx.ok(".env verificado: APP_DEBUG=false y claves de producción presentes");
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
