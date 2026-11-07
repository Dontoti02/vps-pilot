package com.bolsadeploy.deployment;

import com.bolsadeploy.domain.OperationState;
import com.bolsadeploy.domain.StageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Ejecuta las etapas en orden, con eventos de consola, checkpoints y
 * cancelación segura entre etapas. El fallo detiene el proceso (nunca
 * continúa una instalación incompleta) y conserva logs.
 */
public class DeploymentRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRunner.class);

    private final List<DeploymentStage> etapas;

    public DeploymentRunner(List<DeploymentStage> etapas) {
        this.etapas = etapas;
    }

    /**
     * Ejecuta todas las etapas. Devuelve true solo si TODAS terminaron bien
     * (EXITOSA u OMITIDA).
     */
    public boolean ejecutar(DeploymentContext ctx) {
        int total = etapas.size();
        int i = 0;
        boolean todoOk = true;
        for (DeploymentStage etapa : etapas) {
            if (ctx.isCancelado()) {
                ctx.log("[--] Operación cancelada antes de: " + etapa.nombre());
                ctx.listener.onEtapaFinalizada(
                        StageResult.cancelada(etapa.id(), "Cancelada por el usuario"));
                return false;
            }
            i++;
            ctx.listener.onEtapaIniciada(etapa.id(), etapa.nombre());
            ctx.listener.onProgreso((i - 1) * 100 / total);
            long inicio = System.currentTimeMillis();
            try {
                // 1) ¿La etapa aplica en este contexto? (HTTPS no solicitado, etc.)
                String motivo = etapa.motivoOmitida(ctx);
                if (motivo != null && !motivo.isBlank()) {
                    ctx.log("[--] " + etapa.nombre() + " omitida: " + motivo);
                    ctx.listener.onEtapaFinalizada(
                            StageResult.omitida(etapa.id(), motivo));
                    ctx.marcarCheckpoint(etapa.id());
                    continue;
                }
                // 2) ¿Ya estaba cumplida en una ejecución previa?
                if (etapa.yaCumplida(ctx)) {
                    long d = System.currentTimeMillis() - inicio;
                    ctx.log("[OK] " + etapa.nombre() + " (ya cumplida, omitida)");
                    ctx.listener.onEtapaFinalizada(
                            StageResult.omitida(etapa.id(), "Ya cumplida en una ejecución previa"));
                    ctx.marcarCheckpoint(etapa.id());
                    continue;
                }
                etapa.ejecutar(ctx);
                long d = System.currentTimeMillis() - inicio;
                ctx.ok(etapa.nombre());
                ctx.marcarCheckpoint(etapa.id());
                ctx.listener.onEtapaFinalizada(
                        StageResult.ok(etapa.id(), etapa.nombre(), d, ""));
            } catch (StageException e) {
                long d = System.currentTimeMillis() - inicio;
                if (e.getTipo() == StageException.Tipo.CANCELADO) {
                    ctx.log("[--] " + e.getMessage());
                    ctx.listener.onEtapaFinalizada(StageResult.cancelada(etapa.id(), e.getMessage()));
                    return false;
                }
                log.error("Etapa '{}' falló: {}", etapa.id(), e.getMessage(), e);
                ctx.log("[XX] " + etapa.nombre() + ": " + e.getMessage());
                ctx.listener.onEtapaFinalizada(
                        StageResult.fallida(etapa.id(), e.getMessage(), d, e.getMessage()));
                todoOk = false;
                break; // detener: no continuar una instalación incompleta
            } catch (Exception e) {
                long d = System.currentTimeMillis() - inicio;
                log.error("Error inesperado en etapa '{}'", etapa.id(), e);
                ctx.log("[XX] " + etapa.nombre() + ": error inesperado: " + e.getMessage());
                ctx.listener.onEtapaFinalizada(
                        StageResult.fallida(etapa.id(), "Error inesperado: " + e.getMessage(), d, ""));
                todoOk = false;
                break;
            }
        }
        ctx.listener.onProgreso(100);
        return todoOk;
    }

    /** Estados finales posibles para el historial. */
    public static String estadoFinal(boolean exito) {
        return exito ? OperationState.EXITOSA.name() : OperationState.FALLIDA.name();
    }
}
