package com.bolsadeploy.infrastructure.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Conexión única a la base de datos local SQLite. Todas las operaciones se
 * serializan con {@code synchronized} (una app de escritorio no necesita más).
 * WAL mejora la concurrencia lectura/escritura si se usan varios hilos.
 */
public final class Database implements AutoCloseable {

    private final Connection conexion;
    private final Object lock = new Object();

    public Database(Path archivo) throws SQLException {
        try {
            if (archivo.getParent() != null) {
                Files.createDirectories(archivo.getParent());
            }
        } catch (java.io.IOException e) {
            throw new SQLException("No se pudo crear el directorio de la base de datos (" + archivo.getParent() + "): " + e.getMessage(), e);
        }
        this.conexion = DriverManager.getConnection("jdbc:sqlite:" + archivo.toAbsolutePath());
        try (Statement st = conexion.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
        }
        SchemaMigrator.aplicar(conexion);
    }

    /** Abre la base de datos por defecto de la aplicación. */
    public static Database abrirPorDefecto() throws SQLException {
        Path base = resolverDirectorioDatos();
        return new Database(base.resolve("bolsadeploy.sqlite"));
    }

    /** Resuelve la ruta de datos con tolerancia a fallos de permisos en Windows. */
    public static Path resolverDirectorioDatos() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            Path p = Path.of(appdata, "BolsaDeployManager");
            if (probarDirectorio(p)) {
                return p;
            }
        }
        Path userHome = Path.of(System.getProperty("user.home", "."), ".bolsadeploy");
        if (probarDirectorio(userHome)) {
            return userHome;
        }
        Path local = Path.of("data");
        if (probarDirectorio(local)) {
            return local;
        }
        return Path.of(".");
    }

    private static boolean probarDirectorio(Path p) {
        try {
            Files.createDirectories(p);
            Path testFile = p.resolve(".write_test_" + System.currentTimeMillis());
            Files.writeString(testFile, "ok");
            Files.deleteIfExists(testFile);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Ejecuta una operación de lectura devolviendo un valor. */
    public <T> T leer(SqlCall<T> fn) throws SQLException {
        synchronized (lock) {
            return fn.apply(conexion);
        }
    }

    /** Ejecuta una operación de escritura dentro de transacción. */
    public <T> T escribir(SqlCall<T> fn) throws SQLException {
        synchronized (lock) {
            boolean anterior = conexion.getAutoCommit();
            conexion.setAutoCommit(false);
            try {
                T resultado = fn.apply(conexion);
                conexion.commit();
                return resultado;
            } catch (SQLException | RuntimeException e) {
                conexion.rollback();
                throw e;
            } finally {
                conexion.setAutoCommit(anterior);
            }
        }
    }

    public Connection conexion() {
        return conexion;
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                conexion.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface SqlCall<T> {
        T apply(Connection c) throws SQLException;
    }
}
