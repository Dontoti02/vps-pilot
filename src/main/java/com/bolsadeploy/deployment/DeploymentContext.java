package com.bolsadeploy.deployment;

import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.infrastructure.ssh.SshException;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Estado compartido durante un despliegue: conexión SSH, entidades, secretos
 * resueltos, flags del asistente y datos entre etapas.
 * Los valores en {@link #valores} no contienen secretos.
 */
public class DeploymentContext {

    public final VpsServer servidor;
    public final Institution institucion;
    public final AppSettings ajustes;
    public final SshConnection ssh;
    public final DeploymentListener listener;

    // --- Secretos resueltos (jamás se registran en logs) ---
    public String dbPassword = "";
    public String adminNombre = "";
    public String adminEmail = "";
    public String adminPassword = "";
    public String smtpPassword = "";

    // --- Flags del asistente ---
    public boolean instalarHttps = true;
    public boolean instalarRedis = false;
    public boolean adoptarDbExistente = false;
    public boolean esActualizacion = false;

    // --- Datos entre etapas (sin secretos) ---
    public final Map<String, String> valores = new HashMap<>();

    // --- Control ---
    private volatile boolean cancelado = false;
    private volatile String checkpointActual = "";
    private BiConsumer<String, String> checkpointCallback = (id, v) -> {
    };

    public DeploymentContext(VpsServer servidor, Institution institucion, AppSettings ajustes,
                             SshConnection ssh, DeploymentListener listener) {
        this.servidor = servidor;
        this.institucion = institucion;
        this.ajustes = ajustes;
        this.ssh = ssh;
        this.listener = listener == null ? new DeploymentListener() {
        } : listener;
    }

    // ------------------------------------------------------------------
    // Ejecución con logging y cancelación segura
    // ------------------------------------------------------------------

    /** Ejecuta un comando registrándolo en la consola. */
    public CommandResult ejecutar(String comando, int timeoutSeg) throws StageException {
        if (cancelado) {
            throw new StageException(StageException.Tipo.CANCELADO, "Operación cancelada por el usuario.");
        }
        try {
            CommandResult r = ssh.ejecutar(comando, timeoutSeg);
            if (r.exitCode() != 0) {
                listener.onLog("  [!!] exit=" + r.exitCode() + " :: " + recortar(comando));
            }
            return r;
        } catch (SshException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO, e.getMessage(), e);
        }
    }

    /**
     * Ejecuta un comando que DEBE terminar con exit 0; si no, lanza
     * StageException con stderr (la causa real).
     */
    public CommandResult exigirExito(String comando, int timeoutSeg, String descripcion)
            throws StageException {
        CommandResult r = ejecutar(comando, timeoutSeg);
        if (!r.exito()) {
            String detalle = r.salida().isBlank() ? "(sin salida)" : recortar(r.salida());
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    descripcion + " falló (exit " + r.exitCode() + "): " + detalle);
        }
        return r;
    }

    /** Registra una línea en la consola. */
    public void log(String linea) {
        listener.onLog(linea);
    }

    /** Registra un [OK] de etapa. */
    public void ok(String mensaje) {
        listener.onLog("[OK] " + mensaje);
    }

    // ------------------------------------------------------------------
    // Checkpoints (retomada tras interrupción)
    // ------------------------------------------------------------------

    public void onCheckpoint(BiConsumer<String, String> callback) {
        this.checkpointActual = "";
        this.checkpointCallback = callback;
    }

    public void marcarCheckpoint(String etapaId) {
        this.checkpointActual = etapaId;
        try {
            checkpointCallback.accept(String.valueOf(institucion.getId()), etapaId);
        } catch (Exception e) {
            // un fallo de checkpoint no debe tumbar el despliegue
        }
    }

    public String getCheckpointActual() {
        return checkpointActual;
    }

    // ------------------------------------------------------------------
    // Cancelación segura
    // ------------------------------------------------------------------

    /** Pide cancelar: se detiene ANTES del siguiente comando (punto seguro). */
    public void cancelar() {
        this.cancelado = true;
    }

    public boolean isCancelado() {
        return cancelado;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Versión PHP detectada (p.ej. "8.3"), con valor por defecto. */
    public String phpVersion() {
        return valores.getOrDefault("phpVersion", "8.3");
    }

    /** Ruta base de la institución en el VPS. */
    public String rutaBase() {
        return ajustes.getRutaInstalacionBase() + "/" + institucion.getSlug();
    }

    public String rutaReleaseActual() {
        return rutaBase() + "/current";
    }

    public String rutaShared() {
        return rutaBase() + "/shared";
    }

    public String rutaBackups() {
        return rutaBase() + "/backups";
    }

    /**
     * Prefija {@code sudo -n} cuando el usuario SSH no es root. Tras la etapa
     * de diagnóstico el valor de {@code esRoot} siempre está definido; si se
     * invoca sin diagnóstico se asume necesidad de sudo (como root,
     * {@code sudo -n} también funciona).
     */
    public String sudo(String comando) {
        boolean esRoot = "true".equals(valores.getOrDefault("esRoot", "false"));
        return esRoot ? comando : "sudo -n " + comando;
    }

    /** true si el usuario SSH actual es root. */
    public boolean esRoot() {
        return "true".equals(valores.getOrDefault("esRoot", "false"));
    }

    /** Socket php-fpm detectado o predicho, p.ej. {@code unix:/run/php/php8.3-fpm.sock}. */
    public String socketFpm() {
        return valores.getOrDefault("socketFpm", "unix:/run/php/php8.3-fpm.sock");
    }

    /** Servicio MySQL/MariaDB detectado (mysql, mysqld o mariadb). */
    public String servicioMysql() {
        return valores.getOrDefault("mysqlService", "mysql");
    }

    /** Versión objetivo de PHP para esta instalación (8.3 en 24.04, 8.2 en 22.04). */
    public String phpObjetivo() {
        return valores.getOrDefault("phpObjetivo", "8.3");
    }

    private static String recortar(String s) {
        String limpio = s.replaceAll("\\s+", " ").trim();
        return limpio.length() > 400 ? limpio.substring(0, 400) + "…" : limpio;
    }
}
