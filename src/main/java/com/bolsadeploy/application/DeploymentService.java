package com.bolsadeploy.application;

import com.bolsadeploy.deployment.BaseDatosStage;
import com.bolsadeploy.deployment.CodigoStage;
import com.bolsadeploy.deployment.DependenciasAppStage;
import com.bolsadeploy.deployment.DependenciasStage;
import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.DeploymentListener;
import com.bolsadeploy.deployment.DeploymentRunner;
import com.bolsadeploy.deployment.DeploymentStage;
import com.bolsadeploy.deployment.DiagnosticoStage;
import com.bolsadeploy.deployment.DominioNginxStage;
import com.bolsadeploy.deployment.LaravelEnvStage;
import com.bolsadeploy.deployment.MigracionStage;
import com.bolsadeploy.deployment.ServiciosStage;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.deployment.SslStage;
import com.bolsadeploy.deployment.ValidacionStage;
import com.bolsadeploy.domain.DeploymentReport;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.InstitutionStatus;
import com.bolsadeploy.domain.OperationState;
import com.bolsadeploy.domain.StageResult;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.infrastructure.ssh.SshException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Orquesta la instalación de una institución (FASE D + I): resuelve el VPS,
 * conecta por SSH (con diálogo de host key), ejecuta las 11 etapas en orden,
 * persiste el checkpoint por etapa, actualiza el estado de la institución y
 * deja un {@link DeploymentReport} verificable más el registro de auditoría.
 */
public class DeploymentService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppContext app;

    public DeploymentService(AppContext app) {
        this.app = app;
    }

    /** Parámetros del asistente de instalación. */
    public record Solicitud(Institution institucion,
                            boolean https,
                            boolean redis,
                            boolean adoptarDbExistente,
                            String dbPassword,
                            String adminNombre,
                            String adminEmail,
                            String adminPassword,
                            String smtpPassword,
                            String commitObjetivo,
                            String repoGit,
                            String rama,
                            boolean configurarDb,
                            boolean importarEsquemaBase) {

        public Solicitud(Institution institucion,
                         boolean https,
                         boolean redis,
                         boolean adoptarDbExistente,
                         String dbPassword,
                         String adminNombre,
                         String adminEmail,
                         String adminPassword,
                         String smtpPassword,
                         String commitObjetivo) {
            this(institucion, https, redis, adoptarDbExistente, dbPassword, adminNombre,
                    adminEmail, adminPassword, smtpPassword, commitObjetivo, "", "", true, false);
        }
    }

    /** Las 11 etapas en orden. */
    public static List<DeploymentStage> etapas() {
        return List.of(
                new DiagnosticoStage(),
                new DependenciasStage(),
                new BaseDatosStage(),
                new DominioNginxStage(),
                new CodigoStage(),
                new LaravelEnvStage(),
                new DependenciasAppStage(),
                new MigracionStage(),
                new ServiciosStage(),
                new SslStage(),
                new ValidacionStage());
    }

    /**
     * Ejecuta la instalación completa.
     *
     * @param listener consola de la UI (se invoca desde hilos de trabajo)
     * @return reporte final; {@code exitoso=false} si alguna etapa falló
     * @throws StageException si ni siquiera se pudo conectar/operar
     */
    public DeploymentReport ejecutar(Solicitud s, com.bolsadeploy.infrastructure.ssh.HostKeyPrompt prompt,
                                     DeploymentListener listener) throws StageException {
        Institution inst = s.institucion();
        if (inst == null) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "No se indicó la institución a instalar.");
        }
        String checkpointPrevio = inst.getCheckpoint();
        if (checkpointPrevio != null && !checkpointPrevio.isBlank()) {
            listener.onLog("[i] Existía un progreso previo (etapa: " + checkpointPrevio
                    + "). Las etapas ya cumplidas se omitirán automáticamente.");
        }

        long opId = app.auditoria.abrir("DESPLIEGUE", inst.getNombre(), "",
                "Instalación en " + inst.getDominio() + " (HTTPS=" + s.https() + ")");
        List<StageResult> resultados = new ArrayList<>();
        DeploymentContext ctx = null;
        SshConnection ssh = null;
        DeploymentReport reporte = new DeploymentReport();
        reporte.setInstitucion(inst.getNombre());
        reporte.setDominio(inst.getDominio());
        reporte.setFecha(LocalDateTime.now().format(FECHA));

        try {
            VpsServer servidor = app.vpsDe(inst);
            reporte.setServidorNombre(servidor.getNombre());
            reporte.setServidorIp(servidor.getHost());

            inst.setEstado(InstitutionStatus.INSTALANDO);
            app.instituciones.actualizar(inst);

            listener.onLog("[i] Conectando con " + servidor.getUsuario() + "@" + servidor.getHost()
                    + ":" + servidor.getPuerto() + "…");
            ssh = app.conectar(servidor, prompt);
            listener.onLog("[OK] Conexión SSH establecida (huella " + ssh.fingerprintServidor() + ")");

            ctx = app.contexto(servidor, inst, ssh, listener);
            ctx.repoGitObjetivo = s.repoGit() != null ? s.repoGit().trim() : "";
            ctx.ramaObjetivo = s.rama() != null ? s.rama().trim() : "";
            ctx.configurarBaseDatos = s.configurarDb();
            ctx.importarEsquemaBase = s.importarEsquemaBase();
            if (s.importarEsquemaBase()) {
                ctx.valores.put("importarBaseSql", "true");
            }
            ctx.instalarHttps = s.https();
            ctx.instalarRedis = s.redis();
            ctx.adoptarDbExistente = s.adoptarDbExistente();
            ctx.esActualizacion = false;
            ctx.dbPassword = s.dbPassword() == null ? "" : s.dbPassword();
            if (!ctx.dbPassword.isBlank()) {
                try {
                    app.credenciales.guardar(inst.credencialDbId(), ctx.dbPassword);
                } catch (Exception e) {
                    listener.onLog("[!] No se pudo guardar la credencial de BD localmente: " + e.getMessage());
                }
            }
            ctx.adminNombre = s.adminNombre() == null ? "" : s.adminNombre();
            ctx.adminEmail = s.adminEmail() == null ? "" : s.adminEmail();
            ctx.adminPassword = s.adminPassword() == null ? "" : s.adminPassword();
            ctx.smtpPassword = s.smtpPassword() == null ? "" : s.smtpPassword();
            if (s.commitObjetivo() != null && !s.commitObjetivo().isBlank()) {
                ctx.valores.put("commitObjetivo", s.commitObjetivo().trim());
            }
            ctx.onCheckpoint((id, etapa) -> {
                try {
                    app.instituciones.guardarCheckpoint(Long.parseLong(id), etapa);
                } catch (Exception e) {
                    listener.onLog("[!] No se pudo guardar el checkpoint: " + e.getMessage());
                }
            });

            DeploymentListener colector = conectar(listener, resultados);
            boolean ok = new DeploymentRunner(etapas()).ejecutar(ctx);
            reporte.setExitoso(ok);

            // ------------------------------------------- Estado final
            String commit = ctx.valores.getOrDefault("releaseSha", "");
            inst.setCommitDesplegado(commit);
            inst.setCheckpoint(ok ? "" : ctx.getCheckpointActual());
            if (ok) {
                inst.setEstado(InstitutionStatus.INSTALADA);
                inst.setFechaInstalacion(LocalDateTime.now().format(FECHA));
                inst.setUltimoError("");
            } else {
                inst.setEstado(InstitutionStatus.ERROR);
                inst.setUltimoError(primerError(resultados));
            }
            app.instituciones.actualizar(inst);

            // ------------------------------------------------- Reporte
            reporte.setCommit(commit);
            reporte.setVersionDesplegada(commit.length() > 8 ? commit.substring(0, 8) : commit);
            reporte.setEnlace(inst.urlBase());
            reporte.setEstadoSsl(ctx.valores.getOrDefault("sslEstado",
                    s.https() ? "(no evaluado)" : "HTTPS no solicitado"));
            reporte.setServicios(resumenServicios(ctx));
            for (StageResult r : resultados) {
                reporte.getEtapas().add(r);
                if (r.estado() == OperationState.FALLIDA) {
                    reporte.getErrores().add(r.mensaje());
                } else if (r.estado() == OperationState.OMITIDA
                        && !r.mensaje().startsWith("Ya cumplida")) {
                    reporte.getAdvertencias().add(r.mensaje());
                }
            }
            if (ctx.valores.containsKey("smtpOmitido")) {
                reporte.getAdvertencias().add("SMTP no configurado: el correo queda en log.");
            }
            String avisos = ctx.valores.getOrDefault("sslEstado", "");
            if (avisos.startsWith("PENDIENTE")) {
                reporte.getAdvertencias().add("HTTPS: " + avisos);
            }

            app.auditoria.cerrar(opId, ok ? OperationState.EXITOSA : OperationState.FALLIDA,
                    resumenTecnico(resultados, ctx));
            listener.onLog(ok
                    ? "[OK] Instalación completada: " + inst.urlBase()
                    : "[XX] Instalación detenida: " + primerError(resultados));
            return reporte;

        } catch (SshException e) {
            inst.setEstado(InstitutionStatus.ERROR);
            inst.setUltimoError(e.getMessage());
            try {
                app.instituciones.actualizar(inst);
            } catch (Exception ignored) {
            }
            app.auditoria.cerrar(opId, OperationState.FALLIDA, e.getMessage());
            throw new StageException(e.getCodigo() == SshException.Codigo.AUTENTICACION_RECHAZADA
                    ? StageException.Tipo.REQUIERE_USUARIO
                    : StageException.Tipo.TRANSITORIO, e.getMessage(), e);
        } catch (Exception e) {
            app.auditoria.cerrar(opId, OperationState.FALLIDA, e.getMessage());
            throw new StageException(StageException.Tipo.FATAL,
                    "Error inesperado durante la instalación: " + e.getMessage(), e);
        } finally {
            if (ssh != null) {
                try {
                    ssh.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** Devuelve un listener que reenvía eventos y colecta resultados. */
    private static DeploymentListener conectar(DeploymentListener ui, List<StageResult> resultados) {
        return new DeploymentListener() {
            @Override
            public void onEtapaIniciada(String id, String nombre) {
                ui.onEtapaIniciada(id, nombre);
            }

            @Override
            public void onLog(String linea) {
                ui.onLog(linea);
            }

            @Override
            public void onEtapaFinalizada(StageResult resultado) {
                resultados.add(resultado);
                ui.onEtapaFinalizada(resultado);
            }

            @Override
            public void onProgreso(int porcentaje) {
                ui.onProgreso(porcentaje);
            }
        };
    }

    private static String primerError(List<StageResult> resultados) {
        for (StageResult r : resultados) {
            if (r.estado() == OperationState.FALLIDA) {
                return r.mensaje();
            }
        }
        return "Fallo sin detalle";
    }

    private static String resumenServicios(DeploymentContext ctx) {
        String fpm = ctx.valores.getOrDefault("phpFpmService", "php-fpm");
        String mysql = ctx.valores.getOrDefault("mysqlService", "mysql");
        String node = ctx.valores.getOrDefault("nodeVersion", "?");
        return "nginx, " + fpm + ", " + mysql + ", supervisor (Node " + node + ")";
    }

    private static String resumenTecnico(List<StageResult> resultados, DeploymentContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (StageResult r : resultados) {
            sb.append(r.etapaId()).append('=').append(r.estado()).append("; ");
        }
        String ssl = ctx.valores.getOrDefault("sslEstado", "");
        if (!ssl.isBlank()) {
            sb.append("ssl=").append(ssl);
        }
        return sb.toString();
    }
}
