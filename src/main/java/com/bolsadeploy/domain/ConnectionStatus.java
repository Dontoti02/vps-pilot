package com.bolsadeploy.domain;

/** Estado de la última comprobación de conexión a un VPS. */
public enum ConnectionStatus {
    /** Nunca comprobado. */
    SIN_COMPROBAR,
    /** Última comprobación correcta. */
    CONECTADO,
    /** Última comprobación fallida. */
    ERROR
}
