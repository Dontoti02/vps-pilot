package com.bolsadeploy.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Migraciones versionadas del esquema local. Se aplican en orden y quedan
 * registradas en {@code schema_version}; se ejecutan al abrir la base.
 * Nunca se modifica una migración ya aplicada: se añade una nueva.
 */
public final class SchemaMigrator {

    public record Migracion(int version, String sql) {
    }

    private static final List<Migracion> MIGRACIONES = List.of(
            new Migracion(1, """
                    CREATE TABLE IF NOT EXISTS vps_servers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nombre TEXT NOT NULL,
                        host TEXT NOT NULL,
                        puerto INTEGER NOT NULL DEFAULT 22,
                        usuario TEXT NOT NULL DEFAULT 'root',
                        tipo_auth TEXT NOT NULL DEFAULT 'PASSWORD',
                        descripcion TEXT DEFAULT '',
                        grupo TEXT DEFAULT '',
                        estado TEXT NOT NULL DEFAULT 'SIN_COMPROBAR',
                        ultimo_resultado TEXT DEFAULT '',
                        ultimo_check_en TEXT DEFAULT '',
                        creado_en TEXT DEFAULT ''
                    );
                    CREATE TABLE IF NOT EXISTS institutions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nombre TEXT NOT NULL,
                        siglas TEXT DEFAULT '',
                        codigo_interno TEXT DEFAULT '',
                        region TEXT DEFAULT '',
                        dominio TEXT DEFAULT '',
                        usa_www INTEGER NOT NULL DEFAULT 0,
                        email_contacto TEXT DEFAULT '',
                        vps_id INTEGER DEFAULT 0,
                        ruta_instalacion TEXT DEFAULT '',
                        slug TEXT DEFAULT '',
                        repo_git TEXT DEFAULT '',
                        nombre_base_datos TEXT DEFAULT '',
                        usuario_base_datos TEXT DEFAULT '',
                        rama TEXT NOT NULL DEFAULT 'main',
                        email_ssl TEXT DEFAULT '',
                        https_activado INTEGER NOT NULL DEFAULT 0,
                        smtp_host TEXT DEFAULT '',
                        smtp_puerto INTEGER NOT NULL DEFAULT 587,
                        smtp_usuario TEXT DEFAULT '',
                        smtp_from TEXT DEFAULT '',
                        estado TEXT NOT NULL DEFAULT 'PENDIENTE',
                        fecha_instalacion TEXT DEFAULT '',
                        ultima_actualizacion TEXT DEFAULT '',
                        commit_desplegado TEXT DEFAULT '',
                        ultimo_error TEXT DEFAULT '',
                        checkpoint TEXT DEFAULT ''
                    );
                    CREATE TABLE IF NOT EXISTS operations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tipo TEXT NOT NULL,
                        institucion TEXT DEFAULT '',
                        servidor TEXT DEFAULT '',
                        detalle TEXT DEFAULT '',
                        estado TEXT NOT NULL,
                        inicio_en TEXT NOT NULL,
                        fin_en TEXT DEFAULT '',
                        tecnico TEXT DEFAULT ''
                    );
                    CREATE TABLE IF NOT EXISTS backups (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        institucion_id INTEGER NOT NULL,
                        institucion TEXT NOT NULL,
                        tipo TEXT NOT NULL,
                        identificador TEXT NOT NULL,
                        commit_asociado TEXT DEFAULT '',
                        tamano TEXT DEFAULT '',
                        tamano_bytes INTEGER NOT NULL DEFAULT 0,
                        ubicacion TEXT NOT NULL,
                        creado_en TEXT NOT NULL,
                        verificacion TEXT NOT NULL DEFAULT 'PENDIENTE'
                    );
                    CREATE TABLE IF NOT EXISTS settings (
                        clave TEXT PRIMARY KEY,
                        valor TEXT NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_operations_tipo ON operations(tipo);
                    CREATE INDEX IF NOT EXISTS idx_operations_fecha ON operations(inicio_en);
                    CREATE INDEX IF NOT EXISTS idx_backups_inst ON backups(institucion_id);
                    """),
            new Migracion(2, """
                    ALTER TABLE institutions ADD COLUMN repo_git TEXT DEFAULT '';
                    """),
            new Migracion(3, """
                    ALTER TABLE institutions ADD COLUMN tipo_bd TEXT DEFAULT 'MYSQL';
                    ALTER TABLE institutions ADD COLUMN puerto_bd INTEGER DEFAULT 3306;
                    """)
    );

    private SchemaMigrator() {
    }

    public static void aplicar(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        aplicada_en TEXT NOT NULL DEFAULT (datetime('now'))
                    );
                    """);
        }
        int actual = versionActual(c);

        // Si por alguna ejecución anterior incompleta falta la tabla 'settings', forzar ejecución
        boolean faltaEsquema = false;
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='settings'")) {
            faltaEsquema = (!rs.next() || rs.getInt(1) == 0);
        } catch (SQLException ignored) {
            faltaEsquema = true;
        }

        for (Migracion m : MIGRACIONES) {
            if (m.version() > actual || faltaEsquema) {
                ejecutarScript(c, m.sql());
                if (m.version() > actual) {
                    try (Statement st = c.createStatement()) {
                        st.execute("INSERT INTO schema_version (version) VALUES (" + m.version() + ")");
                    } catch (SQLException e) {
                        throw new SQLException("Falló la migración local v" + m.version() + ": " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private static void ejecutarScript(Connection c, String sql) throws SQLException {
        // En SQLite JDBC, st.execute(multiStatementSql) sólo ejecuta la primera sentencia.
        // Se debe separar por punto y coma y ejecutar individualmente cada sentencia no vacía.
        String[] sentencias = sql.split(";");
        try (Statement st = c.createStatement()) {
            for (String raw : sentencias) {
                String sentencia = raw.trim();
                if (!sentencia.isEmpty()) {
                    st.execute(sentencia);
                }
            }
        }
    }

    private static int versionActual(Connection c) throws SQLException {
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
