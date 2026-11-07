package com.bolsadeploy.domain;

/** Metadatos de un respaldo registrado. */
public class BackupRecord {

    private long id;
    private long institucionId;
    private String institucion;
    private String tipo;        // BD | ARCHIVOS | CONFIG | INTEGRAL
    private String identificador;
    private String commitAsociado;
    private String tamano;
    private String ubicacion;   // ruta en el VPS
    private String creadoEn;
    private String verificacion; // OK | FALLIDA | PENDIENTE
    private long tamanoBytes;

    public BackupRecord() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getInstitucionId() {
        return institucionId;
    }

    public void setInstitucionId(long institucionId) {
        this.institucionId = institucionId;
    }

    public String getInstitucion() {
        return institucion;
    }

    public void setInstitucion(String institucion) {
        this.institucion = institucion;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getIdentificador() {
        return identificador;
    }

    public void setIdentificador(String identificador) {
        this.identificador = identificador;
    }

    public String getCommitAsociado() {
        return commitAsociado;
    }

    public void setCommitAsociado(String commitAsociado) {
        this.commitAsociado = commitAsociado;
    }

    public String getTamano() {
        return tamano;
    }

    public void setTamano(String tamano) {
        this.tamano = tamano;
    }

    public String getUbicacion() {
        return ubicacion;
    }

    public void setUbicacion(String ubicacion) {
        this.ubicacion = ubicacion;
    }

    public String getCreadoEn() {
        return creadoEn;
    }

    public void setCreadoEn(String creadoEn) {
        this.creadoEn = creadoEn;
    }

    public String getVerificacion() {
        return verificacion;
    }

    public void setVerificacion(String verificacion) {
        this.verificacion = verificacion;
    }

    public long getTamanoBytes() {
        return tamanoBytes;
    }

    public void setTamanoBytes(long tamanoBytes) {
        this.tamanoBytes = tamanoBytes;
    }
}
