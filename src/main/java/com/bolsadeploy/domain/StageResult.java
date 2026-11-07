package com.bolsadeploy.domain;

/** Resultado de la ejecución de una etapa de despliegue o actualización. */
public record StageResult(String etapaId, OperationState estado, String mensaje, long duracionMs, String salidaTecnica) {

    public static StageResult ok(String etapaId, String mensaje, long duracionMs, String salida) {
        return new StageResult(etapaId, OperationState.EXITOSA, mensaje, duracionMs, salida);
    }

    public static StageResult fallida(String etapaId, String mensaje, long duracionMs, String salida) {
        return new StageResult(etapaId, OperationState.FALLIDA, mensaje, duracionMs, salida);
    }

    public static StageResult omitida(String etapaId, String mensaje) {
        return new StageResult(etapaId, OperationState.OMITIDA, mensaje, 0, "");
    }

    public static StageResult cancelada(String etapaId, String mensaje) {
        return new StageResult(etapaId, OperationState.CANCELADA, mensaje, 0, "");
    }
}
