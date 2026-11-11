package com.bolsadeploy.database;

import java.util.ArrayList;
import java.util.List;

/**
 * Modelos de datos para la gestión y diagnóstico de bases de datos remotas.
 */
public final class DatabaseInfo {

    private String motor; // MYSQL | POSTGRESQL | SQLITE | REDIS
    private String nombre;
    private String ruta = ""; // ruta en disco para SQLite
    private long totalBytes;
    private String tamano = "0 B";
    private int totalTablas;
    private String proyectoAsociado = "-";
    private String estado = "ACTIVA";

    public DatabaseInfo() {
    }

    public DatabaseInfo(String motor, String nombre, String ruta, long totalBytes, String tamano, int totalTablas, String proyectoAsociado) {
        this.motor = motor;
        this.nombre = nombre;
        this.ruta = ruta;
        this.totalBytes = totalBytes;
        this.tamano = tamano;
        this.totalTablas = totalTablas;
        this.proyectoAsociado = proyectoAsociado;
    }

    public String getMotor() {
        return motor;
    }

    public void setMotor(String motor) {
        this.motor = motor;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getRuta() {
        return ruta;
    }

    public void setRuta(String ruta) {
        this.ruta = ruta;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public String getTamano() {
        return tamano;
    }

    public void setTamano(String tamano) {
        this.tamano = tamano;
    }

    public int getTotalTablas() {
        return totalTablas;
    }

    public void setTotalTablas(int totalTablas) {
        this.totalTablas = totalTablas;
    }

    public String getProyectoAsociado() {
        return proyectoAsociado;
    }

    public void setProyectoAsociado(String proyectoAsociado) {
        this.proyectoAsociado = proyectoAsociado;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    // ------------------------------------------------------------------
    // Clases auxiliares
    // ------------------------------------------------------------------

    public static class TableInfo {
        private String nombre;
        private long filas;
        private long bytes;
        private String tamano;
        private String motor;

        public TableInfo(String nombre, long filas, long bytes, String tamano, String motor) {
            this.nombre = nombre;
            this.filas = filas;
            this.bytes = bytes;
            this.tamano = tamano;
            this.motor = motor;
        }

        public String getNombre() { return nombre; }
        public long getFilas() { return filas; }
        public long getBytes() { return bytes; }
        public String getTamano() { return tamano; }
        public String getMotor() { return motor; }
    }

    public static class EngineStatus {
        private final String nombre;
        private final boolean activo;
        private final String estado;
        private final int puerto;
        private final String version;
        private final String socketOCli;

        public EngineStatus(String nombre, boolean activo, String estado, int puerto, String version, String socketOCli) {
            this.nombre = nombre;
            this.activo = activo;
            this.estado = estado;
            this.puerto = puerto;
            this.version = version;
            this.socketOCli = socketOCli;
        }

        public String getNombre() { return nombre; }
        public boolean isActivo() { return activo; }
        public String getEstado() { return estado; }
        public int getPuerto() { return puerto; }
        public String getVersion() { return version; }
        public String getSocketOCli() { return socketOCli; }
    }

    public static class SqlQueryResult {
        private final boolean exito;
        private final List<String> columnas;
        private final List<List<String>> filas;
        private final int filasAfectadas;
        private final long duracionMs;
        private final String mensaje;

        public SqlQueryResult(boolean exito, List<String> columnas, List<List<String>> filas, int filasAfectadas, long duracionMs, String mensaje) {
            this.exito = exito;
            this.columnas = columnas != null ? columnas : new ArrayList<>();
            this.filas = filas != null ? filas : new ArrayList<>();
            this.filasAfectadas = filasAfectadas;
            this.duracionMs = duracionMs;
            this.mensaje = mensaje;
        }

        public boolean isExito() { return exito; }
        public List<String> getColumnas() { return columnas; }
        public List<List<String>> getFilas() { return filas; }
        public int getFilasAfectadas() { return filasAfectadas; }
        public long getDuracionMs() { return duracionMs; }
        public String getMensaje() { return mensaje; }
    }
}

