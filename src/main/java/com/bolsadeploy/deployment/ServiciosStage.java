package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Etapa 9 — Servicios (systemd, Supervisor y cron).
 *
 * <ul>
 *   <li>Habilita y arranca nginx, php-fpm, MySQL y supervisor.</li>
 *   <li>Instala el programa de Supervisor {@code bolsa-<slug>-worker} que
 *       mantiene viva la cola de Laravel apuntando a {@code current} (se
 *       reescribe solo si cambió; verifica RUNNING real con supervisorctl).</li>
 *   <li>Fusiona la crontab de www-data de forma idempotente: las líneas de
 *       esta institución llevan el marcador {@code # bolsa-<slug>}; las líneas
 *       de otros usuarios/instalaciones jamás se borran.</li>
 *   <li>Verifica con conteo real que cron y supervisor quedaron activos (no se
 *       confía en el código de salida).</li>
 * </ul>
 */
public class ServiciosStage implements DeploymentStage {

    @Override
    public String id() {
        return "servicios";
    }

    @Override
    public String nombre() {
        return "Servicios (systemd, Supervisor y cron)";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        try {
            String slug = ctx.institucion.getSlug();
            CommandResult servicios = ctx.ejecutar(
                    "systemctl is-active nginx 2>/dev/null; "
                            + "systemctl is-active " + ctx.servicioMysql() + " 2>/dev/null", 60);
            boolean activos = servicios.salida().contains("active");
            CommandResult conf = ctx.ejecutar(
                    "test -f /etc/supervisor/conf.d/bolsa-" + slug + "-worker.conf && echo SI", 30);
            CommandResult cron = ctx.ejecutar(
                    ctx.sudo("crontab -u www-data -l 2>/dev/null | grep -cF 'bolsa-" + slug + "'"), 60);
            boolean hayCron = aEntero(cron.primeraLinea()) > 0;
            return activos && "SI".equals(conf.primeraLinea().trim()) && hayCron;
        } catch (StageException e) {
            return false;
        }
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String slug = ctx.institucion.getSlug();
        String current = ctx.rutaReleaseActual();
        String shared = ctx.rutaShared();

        // ------------------------------------------------- Servicios base
        ctx.exigirExito(ctx.sudo("systemctl enable --now nginx"), 120, "Activación de nginx");
        ctx.exigirExito(ctx.sudo("systemctl enable --now " + ctx.servicioMysql()), 120,
                "Activación de " + ctx.servicioMysql());
        String fpm = ctx.valores.getOrDefault("phpFpmService", "php" + ctx.phpObjetivo() + "-fpm");
        CommandResult fpmActivo = ctx.ejecutar(ctx.sudo("systemctl enable --now " + fpm), 180);
        if (!fpmActivo.exito()) {
            // nombre alternativo habitual: php-fpm (una sola versión instalada)
            fpmActivo = ctx.ejecutar(ctx.sudo("systemctl enable --now php-fpm"), 120);
            if (fpmActivo.exito()) {
                fpm = "php-fpm";
            }
        }
        ctx.exigirExito(ctx.sudo("systemctl enable --now supervisor"), 120, "Activación de supervisor");
        ctx.ok("Servicios habilitados: nginx, " + ctx.servicioMysql() + ", " + fpm + ", supervisor");

        // -------------------------------------- Supervisor: worker de colas
        String nombrePrograma = "bolsa-" + slug + "-worker";
        String confRemota = "/etc/supervisor/conf.d/" + nombrePrograma + ".conf";
        String contenidoSupervisor = supervisorConf(nombrePrograma, current, shared);

        CommandResult confActual = ctx.ejecutar(
                "cat " + confRemota + " 2>/dev/null || true", 30);
        boolean mismoContenido = confActual.salida().contains(
                "queue:work") && confActual.salida().contains(current);

        if (!mismoContenido) {
            Path local = null;
            String tmp = "/tmp/bolsa-supervisor-" + slug + ".conf";
            try {
                local = Files.createTempFile("bolsa-sup-", ".conf");
                Files.writeString(local, contenidoSupervisor, StandardCharsets.UTF_8);
                ctx.ssh.subir(local, tmp);
                ctx.exigirExito(ctx.sudo("install -m 644 " + tmp + " " + confRemota), 60,
                        "Instalación del programa de Supervisor " + nombrePrograma);
            } catch (IOException e) {
                throw new StageException(StageException.Tipo.FATAL,
                        "No se pudo generar el programa de Supervisor: " + e.getMessage(), e);
            } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "Fallo de conexión subiendo el programa de Supervisor: " + e.getMessage(), e);
            } finally {
                if (local != null) {
                    try {
                        Files.deleteIfExists(local);
                    } catch (IOException ignored) {
                    }
                }
                ctx.ejecutar("rm -f " + tmp, 30);
            }
            ctx.ok("Programa " + nombrePrograma + " instalado");
        } else {
            ctx.log("  Programa de Supervisor ya configurado; se reutiliza.");
        }

        ctx.exigirExito(ctx.sudo("supervisorctl reread && supervisorctl update"), 120,
                "Recarga de la configuración de Supervisor");
        // Arrancar/reiniciar de forma tolerante (si ya corre, restart es seguro)
        ctx.ejecutar(ctx.sudo("supervisorctl start " + nombrePrograma + " 2>/dev/null || true"), 120);
        ctx.ejecutar(ctx.sudo("supervisorctl restart " + nombrePrograma + " 2>/dev/null || true"), 120);

        CommandResult estado = ctx.ejecutar(
                ctx.sudo("supervisorctl status " + nombrePrograma + " 2>/dev/null"), 60);
        String lineaEstado = estado.salida().trim();
        if (!lineaEstado.contains("RUNNING")) {
            // Ver la última salida del worker para explicar el fallo real
            CommandResult logWorker = ctx.ejecutar(
                    "tail -n 20 " + shared + "/storage/logs/worker.log 2>/dev/null || "
                            + "tail -n 20 " + shared + "/storage/logs/laravel.log 2>/dev/null || true", 60);
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "El worker de colas no quedó en RUNNING (estado: '"
                            + (lineaEstado.isBlank() ? "sin estado" : recortar(lineaEstado)) + "'). Último registro: "
                            + recortar(logWorker.salida()));
        }
        ctx.ok("Supervisor: " + recortar(lineaEstado));

        // --------------------------------------------------- Cron de www-data
        instalarCron(ctx, slug, current);

        // ------------------------------------------------------ Verificación
        CommandResult cronFinal = ctx.ejecutar(
                ctx.sudo("crontab -u www-data -l 2>/dev/null | grep -cF 'bolsa-" + slug + "'"), 60);
        int lineasCron = (int) aEntero(cronFinal.primeraLinea());
        if (lineasCron < 1) {
            throw new StageException(StageException.Tipo.FATAL,
                    "La crontab de www-data no contiene las líneas de esta institución tras instalarla.");
        }
        CommandResult serviciosFinales = ctx.ejecutar(
                "for s in nginx " + ctx.servicioMysql() + " " + fpm + " supervisor; do "
                        + "printf '%s=%s\\n' \"$s\" \"$(systemctl is-active $s 2>/dev/null)\"; done", 120);
        for (String linea : serviciosFinales.salida().split("\n")) {
            if (linea.contains("=active") || linea.isBlank()) {
                continue;
            }
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "El servicio '" + linea + "' no quedó activo.");
        }
        ctx.ok("Cron (" + lineasCron + " línea(s) marcadas) y servicios activos verificados");
    }

    // ------------------------------------------------------------------

    /**
     * Fusiona el cron de esta institución conservando todo lo demás.
     * Líneas existentes con el marcador {@code # bolsa-<slug>} se sustituyen;
     * el resto de la crontab queda intacto.
     */
    private void instalarCron(DeploymentContext ctx, String slug, String current) throws StageException {
        String marcador = "bolsa-" + slug;
        String contenido = cronBlock(slug, current);
        Path local = null;
        String tmpBloque = "/tmp/bolsa-cron-" + slug + ".block";
        String tmpNuevo = "/tmp/bolsa-cron-" + slug + ".new";
        try {
            local = Files.createTempFile("bolsa-cron-", ".block");
            Files.writeString(local, contenido, StandardCharsets.UTF_8);
            ctx.ssh.subir(local, tmpBloque);

            String fusion = "( crontab -u www-data -l 2>/dev/null || true ) "
                    + "| grep -vF '" + marcador + "' > " + tmpNuevo + " || true; "
                    + "cat " + tmpBloque + " >> " + tmpNuevo + "; "
                    + "crontab -u www-data " + tmpNuevo + "; "
                    + "rm -f " + tmpBloque + " " + tmpNuevo;
            ctx.exigirExito(ctx.sudo(fusion), 120, "Instalación de la crontab de www-data");
            ctx.ok("crontab de www-data actualizada (marcador #" + marcador + ")");
        } catch (IOException e) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo preparar el cron: " + e.getMessage(), e);
        } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "Fallo de conexión subiendo el cron: " + e.getMessage(), e);
        } finally {
            if (local != null) {
                try {
                    Files.deleteIfExists(local);
                } catch (IOException ignored) {
                }
            }
            ctx.ejecutar("rm -f " + tmpBloque + " " + tmpNuevo, 30);
        }
    }

    /** Contenido del programa de Supervisor (worker de colas de Laravel). */
    static String supervisorConf(String programa, String current, String shared) {
        return """
                ; Generado por BolsaDeploy Manager — no editar a mano
                [program:%s]
                command=php artisan queue:work --sleep=3 --tries=3 --max-time=3600 --memory=256
                directory=%s
                user=www-data
                numprocs=1
                process_name=%%(program_name)s
                autostart=true
                autorestart=true
                startsecs=5
                startretries=5
                stopasgroup=true
                killasgroup=true
                redirect_stderr=true
                stdout_logfile=%s/storage/logs/worker.log
                stdout_logfile_maxbytes=20MB
                stdout_logfile_backups=3
                environment=APP_ENV="production",APP_DEBUG="0"
                """.formatted(programa, current, shared);
    }

    /** Bloque de cron de esta institución; cada línea lleva el marcador. */
    static String cronBlock(String slug, String current) {
        String m = " # bolsa-" + slug;
        return "# --- Inicio: BolsaDeploy Manager (" + slug + ") ---\n"
                + "* * * * * cd " + current + " && php artisan schedule:run >> /dev/null 2>&1" + m + "\n"
                + "# --- Fin: BolsaDeploy Manager (" + slug + ") ---" + m + "\n";
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
        return l.length() > 400 ? l.substring(0, 400) + "…" : l;
    }
}
