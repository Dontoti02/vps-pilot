package com.bolsadeploy.domain;

import java.util.List;

/**
 * Plan de actualización para una institución: qué se va a hacer, con qué
 * riesgo y qué respaldos se crearán. Se muestra antes de confirmar.
 */
public record UpdatePlan(Institution institution, String commitActual, String commitObjetivo,
                         List<String> migracionesPendientes, boolean riesgoDestructivo,
                         List<String> archivosSensibles, String resumenCambios) {

    public boolean requiereAprobacionExplicita() {
        return riesgoDestructivo;
    }
}
