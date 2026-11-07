package com.bolsadeploy.infrastructure.ssh;

/**
 * Error SSH con causa real identificada (nunca mensajes falsos de éxito).
 * Los códigos permiten que la UI muestre la acción correctiva.
 */
public class SshException extends Exception {

    public enum Codigo {
        TIMEOUT,
        PUERTO_CERRADO,
        DNS_NO_RESUELTA,
        AUTENTICACION_RECHAZADA,
        HOST_KEY_DESCONOCIDA,
        HOST_KEY_MODIFICADA,
        SESION_CORTADA,
        PERMISOS,
        DESCONOCIDO
    }

    private final Codigo codigo;

    public SshException(Codigo codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public SshException(Codigo codigo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.codigo = codigo;
    }

    public Codigo getCodigo() {
        return codigo;
    }
}
