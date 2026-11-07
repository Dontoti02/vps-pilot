package com.bolsadeploy.domain;

/**
 * Preferencias generales de la aplicación (persistidas en SQLite).
 */
public class AppSettings {

    public static final String TEMA_OSCURO = "oscuro";
    public static final String TEMA_CLARO = "claro";

    private String tema = TEMA_OSCURO;
    private String carpetaRespaldos = "";
    private String rutaInstalacionBase = "/var/www/bolsadeploy";
    private String repoGitHub = "Dontoti02/bolsalaboralv2";
    private String ramaDefault = "main";
    private String githubToken = ""; // opcional, para API con más cuota
    private int concurrenciaActualizaciones = 2;
    private int puertoSshDefault = 22;
    private int timeoutSshSeg = 30;
    private boolean respaldoPreActualizacion = true;
    private boolean verificarHostKeys = true;
    private boolean onboardingVisto = false;
    private boolean requerirLoginWindows = true;

    public boolean isRequerirLoginWindows() {
        return requerirLoginWindows;
    }

    public void setRequerirLoginWindows(boolean requerirLoginWindows) {
        this.requerirLoginWindows = requerirLoginWindows;
    }

    public boolean isOnboardingVisto() {
        return onboardingVisto;
    }

    public void setOnboardingVisto(boolean onboardingVisto) {
        this.onboardingVisto = onboardingVisto;
    }

    public String getTema() {
        return tema;
    }

    public void setTema(String tema) {
        this.tema = tema;
    }

    public String getCarpetaRespaldos() {
        return carpetaRespaldos;
    }

    public void setCarpetaRespaldos(String carpetaRespaldos) {
        this.carpetaRespaldos = carpetaRespaldos;
    }

    public String getRutaInstalacionBase() {
        return rutaInstalacionBase;
    }

    public void setRutaInstalacionBase(String rutaInstalacionBase) {
        this.rutaInstalacionBase = rutaInstalacionBase;
    }

    public String getRepoGitHub() {
        return repoGitHub;
    }

    public void setRepoGitHub(String repoGitHub) {
        this.repoGitHub = repoGitHub;
    }

    public String getRamaDefault() {
        return ramaDefault;
    }

    public void setRamaDefault(String ramaDefault) {
        this.ramaDefault = ramaDefault;
    }

    public String getGithubToken() {
        return githubToken;
    }

    public void setGithubToken(String githubToken) {
        this.githubToken = githubToken;
    }

    public int getConcurrenciaActualizaciones() {
        return concurrenciaActualizaciones;
    }

    public void setConcurrenciaActualizaciones(int concurrenciaActualizaciones) {
        this.concurrenciaActualizaciones = concurrenciaActualizaciones;
    }

    public int getPuertoSshDefault() {
        return puertoSshDefault;
    }

    public void setPuertoSshDefault(int puertoSshDefault) {
        this.puertoSshDefault = puertoSshDefault;
    }

    public int getTimeoutSshSeg() {
        return timeoutSshSeg;
    }

    public void setTimeoutSshSeg(int timeoutSshSeg) {
        this.timeoutSshSeg = timeoutSshSeg;
    }

    public boolean isRespaldoPreActualizacion() {
        return respaldoPreActualizacion;
    }

    public void setRespaldoPreActualizacion(boolean respaldoPreActualizacion) {
        this.respaldoPreActualizacion = respaldoPreActualizacion;
    }

    public boolean isVerificarHostKeys() {
        return verificarHostKeys;
    }

    public void setVerificarHostKeys(boolean verificarHostKeys) {
        this.verificarHostKeys = verificarHostKeys;
    }
}
