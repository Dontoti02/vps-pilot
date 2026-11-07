package com.bolsadeploy.deployment;

import com.bolsadeploy.domain.StageResult;

/** Eventos que el despliegue emite a la consola de operaciones de la UI. */
public interface DeploymentListener {

    /** Comienza una etapa. */
    default void onEtapaIniciada(String id, String nombre) {
    }

    /** Línea de log para la consola (sin secretos). */
    default void onLog(String linea) {
    }

    /** Termina una etapa con su resultado. */
    default void onEtapaFinalizada(StageResult resultado) {
    }

    /** Progreso global estimado 0..100. */
    default void onProgreso(int porcentaje) {
    }
}
