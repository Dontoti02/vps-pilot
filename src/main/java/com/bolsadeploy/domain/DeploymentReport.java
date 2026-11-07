package com.bolsadeploy.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Reporte final de un despliegue (PASO 14 del prompt): se muestra en la UI y
 * se exporta a HTML.
 */
public class DeploymentReport {

    private String institucion;
    private String servidorIp;
    private String servidorNombre;
    private String dominio;
    private String fecha;
    private String versionDesplegada;
    private String commit;
    private String servicios;
    private String estadoSsl;
    private String enlace;
    private boolean exitoso;
    private final List<StageResult> etapas = new ArrayList<>();
    private final List<String> errores = new ArrayList<>();
    private final List<String> advertencias = new ArrayList<>();

    public String getInstitucion() {
        return institucion;
    }

    public void setInstitucion(String institucion) {
        this.institucion = institucion;
    }

    public String getServidorIp() {
        return servidorIp;
    }

    public void setServidorIp(String servidorIp) {
        this.servidorIp = servidorIp;
    }

    public String getServidorNombre() {
        return servidorNombre;
    }

    public void setServidorNombre(String servidorNombre) {
        this.servidorNombre = servidorNombre;
    }

    public String getDominio() {
        return dominio;
    }

    public void setDominio(String dominio) {
        this.dominio = dominio;
    }

    public String getFecha() {
        return fecha;
    }

    public void setFecha(String fecha) {
        this.fecha = fecha;
    }

    public String getVersionDesplegada() {
        return versionDesplegada;
    }

    public void setVersionDesplegada(String versionDesplegada) {
        this.versionDesplegada = versionDesplegada;
    }

    public String getCommit() {
        return commit;
    }

    public void setCommit(String commit) {
        this.commit = commit;
    }

    public String getServicios() {
        return servicios;
    }

    public void setServicios(String servicios) {
        this.servicios = servicios;
    }

    public String getEstadoSsl() {
        return estadoSsl;
    }

    public void setEstadoSsl(String estadoSsl) {
        this.estadoSsl = estadoSsl;
    }

    public String getEnlace() {
        return enlace;
    }

    public void setEnlace(String enlace) {
        this.enlace = enlace;
    }

    public boolean isExitoso() {
        return exitoso;
    }

    public void setExitoso(boolean exitoso) {
        this.exitoso = exitoso;
    }

    public List<StageResult> getEtapas() {
        return etapas;
    }

    public List<String> getErrores() {
        return errores;
    }

    public List<String> getAdvertencias() {
        return advertencias;
    }
}
