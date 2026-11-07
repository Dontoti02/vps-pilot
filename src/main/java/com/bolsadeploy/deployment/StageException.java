package com.bolsadeploy.deployment;

/** Fallo de una etapa de despliegue con mensaje técnico explicado en español. */
public class StageException extends Exception {

    /** Código para que la UI decida reintento, acción correctiva o aborto. */
    public enum Tipo {
        /** Fallo transitorio: reintento razonable. */
        TRANSITORIO,
        /** Requiere intervención del usuario (credenciales, DNS, aprobación). */
        REQUIERE_USUARIO,
        /** Riesgo detectado: requiere aprobación explícita. */
        REQUIERE_APROBACION,
        /** Estado inexistente detectado (p.ej. base ya existente). */
        ESTADO_CONFLICTIVO,
        CANCELADO,
        FATAL
    }

    private final Tipo tipo;

    public StageException(Tipo tipo, String mensaje) {
        super(mensaje);
        this.tipo = tipo;
    }

    public StageException(Tipo tipo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.tipo = tipo;
    }

    public Tipo getTipo() {
        return tipo;
    }
}
