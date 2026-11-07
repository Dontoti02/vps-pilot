package com.bolsadeploy.domain;

/** Tipo de autenticación SSH admitida para un VPS. */
public enum AuthType {
    PASSWORD("Contraseña"),
    KEY("Clave privada SSH");

    private final String etiqueta;

    AuthType(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
