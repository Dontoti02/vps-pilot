package com.bolsadeploy.domain;

/**
 * Servidor VPS Ubuntu registrado. No contiene secretos: las contraseñas y
 * claves privadas se guardan cifradas en el almacén de credenciales (DPAPI)
 * con identificador {@code vps-<id>}.
 */
public class VpsServer {

    private long id;
    private String nombre;
    private String host;
    private int puerto = 22;
    private String usuario = "root";
    private AuthType tipoAuth = AuthType.PASSWORD;
    private String descripcion = "";
    private String grupo = "";
    private ConnectionStatus estado = ConnectionStatus.SIN_COMPROBAR;
    private String ultimoResultado = "";
    private String ultimoCheckEn = "";
    private String creadoEn = "";

    public VpsServer() {
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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPuerto() {
        return puerto;
    }

    public void setPuerto(int puerto) {
        this.puerto = puerto;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public AuthType getTipoAuth() {
        return tipoAuth;
    }

    public void setTipoAuth(AuthType tipoAuth) {
        this.tipoAuth = tipoAuth;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getGrupo() {
        return grupo;
    }

    public void setGrupo(String grupo) {
        this.grupo = grupo;
    }

    public ConnectionStatus getEstado() {
        return estado;
    }

    public void setEstado(ConnectionStatus estado) {
        this.estado = estado;
    }

    public String getUltimoResultado() {
        return ultimoResultado;
    }

    public void setUltimoResultado(String ultimoResultado) {
        this.ultimoResultado = ultimoResultado;
    }

    public String getUltimoCheckEn() {
        return ultimoCheckEn;
    }

    public void setUltimoCheckEn(String ultimoCheckEn) {
        this.ultimoCheckEn = ultimoCheckEn;
    }

    public String getCreadoEn() {
        return creadoEn;
    }

    public void setCreadoEn(String creadoEn) {
        this.creadoEn = creadoEn;
    }

    /** Identificador de credencial en el almacén cifrado. */
    public String credencialId() {
        return "vps-" + id;
    }

    @Override
    public String toString() {
        return nombre + " (" + host + ":" + puerto + ")";
    }
}
