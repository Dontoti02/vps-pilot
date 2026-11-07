package com.bolsadeploy.domain;

/**
 * Registro de historial (despliegues, actualizaciones, respaldos, errores,
 * mantenimiento). Se persiste en SQLite y se muestra en Historial y Registros.
 */
public class OperationRecord {

    private long id;
    private String tipo;        // DESPLIEGUE | ACTUALIZACION | RESPALDO | RESTAURACION | DIAGNOSTICO | MANTENIMIENTO
    private String institucion; // nombre de la institución (o "-")
    private String servidor;    // host del VPS (o "-")
    private String detalle;
    private String estado;      // OperationState.name()
    private String inicioEn;
    private String finEn;
    private String tecnico;     // salida técnica resumida (sin secretos)

    public OperationRecord() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getInstitucion() {
        return institucion;
    }

    public void setInstitucion(String institucion) {
        this.institucion = institucion;
    }

    public String getServidor() {
        return servidor;
    }

    public void setServidor(String servidor) {
        this.servidor = servidor;
    }

    public String getDetalle() {
        return detalle;
    }

    public void setDetalle(String detalle) {
        this.detalle = detalle;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getInicioEn() {
        return inicioEn;
    }

    public void setInicioEn(String inicioEn) {
        this.inicioEn = inicioEn;
    }

    public String getFinEn() {
        return finEn;
    }

    public void setFinEn(String finEn) {
        this.finEn = finEn;
    }

    public String getTecnico() {
        return tecnico;
    }

    public void setTecnico(String tecnico) {
        this.tecnico = tecnico;
    }
}
