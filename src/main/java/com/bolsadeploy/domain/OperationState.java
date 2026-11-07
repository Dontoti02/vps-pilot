package com.bolsadeploy.domain;

/** Estado de una etapa u operación. */
public enum OperationState {
    PENDIENTE,
    EN_CURSO,
    EXITOSA,
    FALLIDA,
    OMITIDA,
    CANCELADA
}
