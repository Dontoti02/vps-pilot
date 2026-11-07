package com.bolsadeploy.application;

import com.bolsadeploy.backups.BackupService;
import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.DeploymentListener;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.domain.BackupRecord;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.ServerDiagnostic;
import com.bolsadeploy.domain.UpdatePlan;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.github.GithubException;
import com.bolsadeploy.infrastructure.ssh.HostKeyPrompt;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.infrastructure.ssh.SshException;
import com.bolsadeploy.monitoring.MonitorService;
import com.bolsadeploy.updates.MassUpdateRunner;
import com.bolsadeploy.updates.UpdateExecutor;
import com.bolsadeploy.updates.UpdatePlanner;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mantenimiento (FASE E, F, G): conexión de prueba, diagnóstico, comprobación
 * web, respaldos y actualizaciones. Cada operación que necesita SSH abre su
 * propia conexión con el diálogo de host key y la cierra siempre.
 */
public class MaintenanceService {

    /** Acción que recibe un contexto conectado. */
    @FunctionalInterface
    public interface Accion {
        void aplicar(DeploymentContext ctx) throws StageException;
    }

    private final AppContext app;

    public MaintenanceService(AppContext app) {
        this.app = app;
    }

    // ------------------------------------------------------------------
    // Conexión de prueba
    // ------------------------------------------------------------------

    /** Resultado de la prueba de conexión a un VPS. */
    public record ResultadoConexion(boolean ok, String detalle) {
    }

    /** Conecta, ejecuta `uname -a` y registra el estado real del VPS. */
    public ResultadoConexion probarConexion(VpsServer servidor, HostKeyPrompt prompt) {
        try (SshConnection ssh = app.conectar(servidor, prompt)) {
            var r = ssh.ejecutar("uname -a && hostname", 30);
            String detalle = r.exito()
                    ? "Conectado. " + r.primeraLinea().trim()
                    : "Conectado pero el comando falló: " + r.salida().trim();
            servidor.setEstado(com.bolsadeploy.domain.ConnectionStatus.CONECTADO);
            servidor.setUltimoResultado(detalle);
            servidor.setUltimoCheckEn(ahora());
            app.vps.actualizar(servidor);
            app.auditoria.registrar("MANTENIMIENTO", "-", servidor.getHost(),
                    "Prueba de conexión SSH", com.bolsadeploy.domain.OperationState.EXITOSA, detalle);
            return new ResultadoConexion(r.exito(), detalle);
        } catch (SshException e) {
            servidor.setEstado(com.bolsadeploy.domain.ConnectionStatus.ERROR);
            servidor.setUltimoResultado(e.getMessage());
            servidor.setUltimoCheckEn(ahora());
            try {
                app.vps.actualizar(servidor);
            } catch (SQLException ignored) {
            }
            app.auditoria.registrar("MANTENIMIENTO", "-", servidor.getHost(),
                    "Prueba de conexión SSH", com.bolsadeploy.domain.OperationState.FALLIDA,
                    e.getMessage());
            return new ResultadoConexion(false, e.getMessage());
        } catch (Exception e) {
            return new ResultadoConexion(false, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Monitoreo
    // ------------------------------------------------------------------

    /** Diagnóstico SSH completo de la institución (FASE G). */
    public ServerDiagnostic diagnosticar(Institution inst, HostKeyPrompt prompt,
                                         DeploymentListener listener) {
        long op = app.auditoria.abrir("DIAGNOSTICO", inst.getNombre(), "",
                "Diagnóstico bajo demanda");
        try (SshConnection ssh = app.conectar(app.vpsDe(inst), prompt)) {
            DeploymentContext ctx = app.contexto(app.vpsDe(inst), inst, ssh,
                    listener == null ? new DeploymentListener() {
                    } : listener);
            ServerDiagnostic d = app.monitoreo.diagnosticar(ctx);
            app.auditoria.cerrar(op, d.sshOk()
                    ? com.bolsadeploy.domain.OperationState.EXITOSA
                    : com.bolsadeploy.domain.OperationState.FALLIDA, d.resumen());
            return d;
        } catch (Exception e) {
            app.auditoria.cerrar(op, com.bolsadeploy.domain.OperationState.FALLIDA, e.getMessage());
            return new ServerDiagnostic(false, e.getMessage(), List.of());
        }
    }

    /** Comprobación HTTP/TLS del sitio desde la máquina local. */
    public MonitorService.HttpCheck comprobarWeb(Institution inst) {
        String url = inst.urlBase();
        return app.monitoreo.comprobarHttp(url, "Oportunidades Laborales");
    }

    // ------------------------------------------------------------------
    // Respaldos (FASE F)
    // ------------------------------------------------------------------

    public BackupRecord crearRespaldo(Institution inst, BackupService.Tipo tipo, String commit,
                                      HostKeyPrompt prompt, DeploymentListener listener)
            throws StageException {
        return conContexto(inst, prompt, listener, "RESPALDO",
                "Respaldo " + tipo + " de " + inst.getNombre(), ctx -> {
                    BackupRecord b = app.backups.crear(ctx, tipo, commit == null ? "" : commit);
                    guardarRegistro(b);
                    return b;
                });
    }

    /** Lista los respaldos existentes en el VPS. */
    public List<BackupRecord> listarRespaldos(Institution inst, HostKeyPrompt prompt,
                                              DeploymentListener listener) throws StageException {
        return conContexto(inst, prompt, listener, "RESPALDO",
                "Listado de respaldos de " + inst.getNombre(),
                ctx -> app.backups.listarEnServidor(ctx));
    }

    /** Descarga un respaldo del VPS a la carpeta local configurada. */
    public Path descargarRespaldo(Institution inst, String archivoRemoto, HostKeyPrompt prompt,
                                  DeploymentListener listener) throws StageException {
        return conContexto(inst, prompt, listener, "RESPALDO",
                "Descarga de respaldo", ctx ->
                        app.backups.descargar(ctx, archivoRemoto, app.carpetaRespaldosLocal()));
    }

    /** Restaura un respaldo (destructivo: requiere confirmación). */
    public String restaurarRespaldo(Institution inst, BackupService.Tipo tipo, String archivo,
                                    boolean confirmado, HostKeyPrompt prompt,
                                    DeploymentListener listener) throws StageException {
        String resumen = conContexto(inst, prompt, listener, "RESTAURACION",
                "Restauración de " + tipo + " en " + inst.getNombre(), ctx ->
                        app.backups.restaurar(ctx, tipo, archivo, confirmado));
        app.auditoria.registrar("RESTAURACION", inst.getNombre(), inst.getDominio(),
                "Restauración de " + tipo + " desde " + archivo,
                com.bolsadeploy.domain.OperationState.EXITOSA, resumen);
        return resumen;
    }

    public int purgarRespaldos(Institution inst, int conservar, HostKeyPrompt prompt,
                               DeploymentListener listener) throws StageException {
        return conContexto(inst, prompt, listener, "RESPALDO",
                "Purga de respaldos (conservar " + conservar + ")",
                ctx -> app.backups.purgar(ctx, conservar));
    }

    // ------------------------------------------------------------------
    // Actualizaciones (FASE E)
    // ------------------------------------------------------------------

    /** Plan de actualización consultando GitHub (sin tocar el servidor). */
    public UpdatePlan planearActualizacion(Institution inst) throws GithubException {
        String commit = inst.getCommitDesplegado();
        if (commit == null || commit.isBlank()) {
            // Sin SHA registrado: se intenta leer del VPS más adelante; por ahora
            // se planifica desde "desconocido" para mostrar los cambios recientes.
            commit = "";
        }
        return UpdatePlanner.planear(app.github, app.ajustes, inst, commit);
    }

    /** Ejecuta la actualización de una institución. */
    public UpdateExecutor.Resultado actualizar(Institution inst, UpdatePlan plan, boolean aprobadoRiesgo,
                                               HostKeyPrompt prompt, DeploymentListener listener)
            throws StageException {
        long op = app.auditoria.abrir("ACTUALIZACION", inst.getNombre(), "",
                "Actualización a " + plan.commitObjetivo());
        VpsServer servidor = vpsDe(inst);
        try (SshConnection ssh = app.conectar(servidor, prompt)) {
            DeploymentContext ctx = app.contexto(servidor, inst, ssh,
                    listener == null ? new DeploymentListener() {
                    } : listener);
            UpdateExecutor.Resultado r = app.actualizaciones.ejecutar(ctx, plan, aprobadoRiesgo,
                    app.backups);
            if (r.exito()) {
                inst.setCommitDesplegado(r.commitNuevo());
                inst.setUltimaActualizacion(ahora());
                inst.setUltimoError("");
                try {
                    app.instituciones.actualizar(inst);
                } catch (SQLException e) {
                    ctx.log("[!] No se pudo actualizar el registro: " + e.getMessage());
                }
            } else {
                inst.setUltimoError(r.mensaje());
                try {
                    app.instituciones.actualizar(inst);
                } catch (SQLException ignored) {
                }
            }
            app.auditoria.cerrar(op, r.exito()
                    ? com.bolsadeploy.domain.OperationState.EXITOSA
                    : com.bolsadeploy.domain.OperationState.FALLIDA, r.mensaje());
            return r;
        } catch (SshException e) {
            app.auditoria.cerrar(op, com.bolsadeploy.domain.OperationState.FALLIDA, e.getMessage());
            throw new StageException(StageException.Tipo.TRANSITORIO, e.getMessage(), e);
        }
    }

    /**
     * Actualiza varias instituciones con la concurrencia configurada; cada
     * resultado se reporta por separado (una fallo no detiene a las demás).
     */
    public List<MassUpdateRunner.Resultado> actualizarMasivo(List<Institution> instituciones,
                                                             HostKeyPrompt prompt,
                                                             java.util.function.Consumer<String> log)
            throws GithubException {
        List<MassUpdateRunner.InstitucionTarea> tareas = new ArrayList<>();
        for (Institution inst : instituciones) {
            UpdatePlan plan = planearActualizacion(inst);
            if (!plan.requiereAprobacionExplicita()) {
                tareas.add(new MassUpdateRunner.InstitucionTarea(inst.getId(), inst.getNombre(),
                        () -> {
                            DeploymentListener l = new DeploymentListener() {
                                @Override
                                public void onLog(String linea) {
                                    if (log != null) {
                                        log.accept("[" + inst.getNombre() + "] " + linea);
                                    }
                                }
                            };
                            UpdateExecutor.Resultado r = actualizar(inst, plan, false, prompt, l);
                            return r.mensaje();
                        }));
            } else {
                if (log != null) {
                    log.accept("[" + inst.getNombre() + "] OMITIDA: requiere aprobación explícita "
                            + "(riesgo destructivo). Revíselo individualmente.");
                }
            }
        }
        MassUpdateRunner runner = new MassUpdateRunner();
        return runner.ejecutar(tareas, app.ajustes.getConcurrenciaActualizaciones(),
                r -> {
                    if (log != null) {
                        log.accept((r.exito() ? "[OK] " : "[XX] ") + r.nombre() + ": " + r.mensaje());
                    }
                });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Abre SSH, ejecuta la acción y cierra siempre; deja auditoría. */
    private <T> T conContexto(Institution inst, HostKeyPrompt prompt, DeploymentListener listener,
                              String tipoOp, String detalle, AccionConRetorno<T> accion)
            throws StageException {
        long op = app.auditoria.abrir(tipoOp, inst.getNombre(), "", detalle);
        VpsServer servidor = vpsDe(inst);
        try (SshConnection ssh = app.conectar(servidor, prompt)) {
            DeploymentContext ctx = app.contexto(servidor, inst, ssh,
                    listener == null ? new DeploymentListener() {
                    } : listener);
            T resultado = accion.aplicar(ctx);
            app.auditoria.cerrar(op, com.bolsadeploy.domain.OperationState.EXITOSA,
                    String.valueOf(resultado));
            return resultado;
        } catch (StageException e) {
            app.auditoria.cerrar(op, com.bolsadeploy.domain.OperationState.FALLIDA, e.getMessage());
            throw e;
        } catch (SshException e) {
            app.auditoria.cerrar(op, com.bolsadeploy.domain.OperationState.FALLIDA, e.getMessage());
            throw new StageException(StageException.Tipo.TRANSITORIO, e.getMessage(), e);
        } catch (Exception e) {
            app.auditoria.cerrar(op, com.bolsadeploy.domain.OperationState.FALLIDA, e.getMessage());
            throw new StageException(StageException.Tipo.FATAL, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface AccionConRetorno<T> {
        T aplicar(DeploymentContext ctx) throws StageException;
    }

    private void guardarRegistro(BackupRecord b) {
        try {
            app.respaldos.guardar(b);
        } catch (SQLException e) {
            // no romper el respaldo por un fallo de auditoría local
            org.slf4j.LoggerFactory.getLogger(MaintenanceService.class)
                    .warn("No se pudo persistir el registro del respaldo: {}", e.getMessage());
        }
    }

    /** VPS de la institución traduciendo el error a un StageException explicado. */
    private VpsServer vpsDe(Institution inst) throws StageException {
        try {
            return app.vpsDe(inst);
        } catch (SQLException | IllegalStateException e) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, e.getMessage(), e);
        }
    }

    private static String ahora() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
