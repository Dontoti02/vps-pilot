package com.bolsadeploy.domain;

/**
 * Institución = una instalación independiente de la bolsa laboral en un VPS.
 * Los secretos (contraseña de BD, SMTP) se guardan cifrados con identificador
 * {@code inst-<id>-db}, {@code inst-<id>-smtp}.
 */
public class Institution {

    private long id;
    private String nombre;
    private String siglas = "";
    private String codigoInterno = "";
    private String region = "";
    private String dominio = "";
    private boolean usaWww = false;
    private String emailContacto = "";
    private long vpsId;
    private String rutaInstalacion = "";
    private String slug = "";
    private String repoGit = "";
    private String tipoBaseDatos = "MYSQL"; // MYSQL | POSTGRESQL | SQLITE | NINGUNA
    private int puertoBaseDatos = 3306;
    private String nombreBaseDatos = "";
    private String usuarioBaseDatos = "";
    private String rama = "main";
    private String emailSsl = "";
    private boolean httpsActivado = false;

    // Configuración SMTP opcional
    private String smtpHost = "";
    private int smtpPuerto = 587;
    private String smtpUsuario = "";
    private String smtpFrom = "";

    private InstitutionStatus estado = InstitutionStatus.PENDIENTE;
    private String fechaInstalacion = "";
    private String ultimaActualizacion = "";
    private String commitDesplegado = "";
    private String ultimoError = "";
    /** Etapa de checkpoint para retomar tras una interrupción (vacío = sin progreso). */
    private String checkpoint = "";

    public Institution() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getSiglas() {
        return siglas;
    }

    public void setSiglas(String siglas) {
        this.siglas = siglas;
    }

    public String getCodigoInterno() {
        return codigoInterno;
    }

    public void setCodigoInterno(String codigoInterno) {
        this.codigoInterno = codigoInterno;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDominio() {
        return dominio;
    }

    public void setDominio(String dominio) {
        this.dominio = dominio;
    }

    public boolean isUsaWww() {
        return usaWww;
    }

    public void setUsaWww(boolean usaWww) {
        this.usaWww = usaWww;
    }

    public String getEmailContacto() {
        return emailContacto;
    }

    public void setEmailContacto(String emailContacto) {
        this.emailContacto = emailContacto;
    }

    public long getVpsId() {
        return vpsId;
    }

    public void setVpsId(long vpsId) {
        this.vpsId = vpsId;
    }

    public String getRutaInstalacion() {
        return rutaInstalacion;
    }

    public void setRutaInstalacion(String rutaInstalacion) {
        this.rutaInstalacion = rutaInstalacion;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getNombreBaseDatos() {
        return nombreBaseDatos;
    }

    public void setNombreBaseDatos(String nombreBaseDatos) {
        this.nombreBaseDatos = nombreBaseDatos;
    }

    public String getTipoBaseDatos() {
        return tipoBaseDatos != null && !tipoBaseDatos.isBlank() ? tipoBaseDatos.toUpperCase() : "MYSQL";
    }

    public void setTipoBaseDatos(String tipoBaseDatos) {
        this.tipoBaseDatos = tipoBaseDatos != null && !tipoBaseDatos.isBlank() ? tipoBaseDatos.trim().toUpperCase() : "MYSQL";
    }

    public int getPuertoBaseDatos() {
        return puertoBaseDatos > 0 ? puertoBaseDatos : ("POSTGRESQL".equalsIgnoreCase(tipoBaseDatos) ? 5432 : 3306);
    }

    public void setPuertoBaseDatos(int puertoBaseDatos) {
        this.puertoBaseDatos = puertoBaseDatos;
    }

    public String getUsuarioBaseDatos() {
        return usuarioBaseDatos;
    }

    public void setUsuarioBaseDatos(String usuarioBaseDatos) {
        this.usuarioBaseDatos = usuarioBaseDatos;
    }

    public String getRepoGit() {
        return repoGit;
    }

    public void setRepoGit(String repoGit) {
        this.repoGit = repoGit != null ? repoGit.trim() : "";
    }

    /**
     * Retorna el repositorio Git configurado para esta institución o proyecto,
     * o recurre al repositorio por defecto de ajustes si está vacío.
     */
    public String repositorioEfectivo(AppSettings ajustes) {
        if (repoGit != null && !repoGit.isBlank()) {
            return repoGit.trim();
        }
        return (ajustes != null && ajustes.getRepoGitHub() != null) ? ajustes.getRepoGitHub().trim() : "";
    }

    public String getRama() {
        return rama;
    }

    public void setRama(String rama) {
        this.rama = rama;
    }

    public String getEmailSsl() {
        return emailSsl;
    }

    public void setEmailSsl(String emailSsl) {
        this.emailSsl = emailSsl;
    }

    public boolean isHttpsActivado() {
        return httpsActivado;
    }

    public void setHttpsActivado(boolean httpsActivado) {
        this.httpsActivado = httpsActivado;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public int getSmtpPuerto() {
        return smtpPuerto;
    }

    public void setSmtpPuerto(int smtpPuerto) {
        this.smtpPuerto = smtpPuerto;
    }

    public String getSmtpUsuario() {
        return smtpUsuario;
    }

    public void setSmtpUsuario(String smtpUsuario) {
        this.smtpUsuario = smtpUsuario;
    }

    public String getSmtpFrom() {
        return smtpFrom;
    }

    public void setSmtpFrom(String smtpFrom) {
        this.smtpFrom = smtpFrom;
    }

    public InstitutionStatus getEstado() {
        return estado;
    }

    public void setEstado(InstitutionStatus estado) {
        this.estado = estado;
    }

    public String getFechaInstalacion() {
        return fechaInstalacion;
    }

    public void setFechaInstalacion(String fechaInstalacion) {
        this.fechaInstalacion = fechaInstalacion;
    }

    public String getUltimaActualizacion() {
        return ultimaActualizacion;
    }

    public void setUltimaActualizacion(String ultimaActualizacion) {
        this.ultimaActualizacion = ultimaActualizacion;
    }

    public String getCommitDesplegado() {
        return commitDesplegado;
    }

    public void setCommitDesplegado(String commitDesplegado) {
        this.commitDesplegado = commitDesplegado;
    }

    public String getUltimoError() {
        return ultimoError;
    }

    public void setUltimoError(String ultimoError) {
        this.ultimoError = ultimoError;
    }

    public String getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(String checkpoint) {
        this.checkpoint = checkpoint;
    }

    /** Dominio completo considerando el uso opcional de www. */
    public String dominioPrincipal() {
        return usaWww ? "www." + dominio : dominio;
    }

    /** URL base de la aplicación. */
    public String urlBase() {
        return (httpsActivado ? "https://" : "http://") + dominioPrincipal();
    }

    public String credencialDbId() {
        return "inst-" + id + "-db";
    }

    public String credencialSmtpId() {
        return "inst-" + id + "-smtp";
    }

    @Override
    public String toString() {
        return nombre + (siglas == null || siglas.isBlank() ? "" : " (" + siglas + ")");
    }
}
