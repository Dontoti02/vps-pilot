package com.bolsadeploy.domain;

/** Estado de despliegue de una institución. */
public enum InstitutionStatus {
    PENDIENTE("Pendiente de instalación"),
    INSTALANDO("Instalando"),
    INSTALADA("Instalada"),
    ACTUALIZANDO("Actualizando"),
    ERROR("Error");

    private final String etiqueta;

    InstitutionStatus(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
