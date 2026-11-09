package com.bolsadeploy.infrastructure.persistence;

import com.bolsadeploy.domain.BackupRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositorio de respaldos. */
public class BackupRepository {

    private final Database db;

    public BackupRepository(Database db) {
        this.db = db;
    }

    public long guardar(BackupRecord b) throws SQLException {
        return db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "INSERT INTO backups (institucion_id, institucion, tipo, identificador, commit_asociado,"
                            + " tamano, tamano_bytes, ubicacion, creado_en, verificacion)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                st.setLong(1, b.getInstitucionId());
                st.setString(2, b.getInstitucion());
                st.setString(3, b.getTipo());
                st.setString(4, b.getIdentificador());
                st.setString(5, b.getCommitAsociado() == null ? "" : b.getCommitAsociado());
                st.setString(6, b.getTamano() == null ? "" : b.getTamano());
                st.setLong(7, b.getTamanoBytes());
                st.setString(8, b.getUbicacion());
                st.setString(9, b.getCreadoEn());
                st.setString(10, b.getVerificacion() == null ? "PENDIENTE" : b.getVerificacion());
                st.executeUpdate();
                try (var keys = st.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    b.setId(id);
                    return id;
                }
            }
        });
    }

    public void actualizarVerificacion(long id, String verificacion, String tamano, long tamanoBytes)
            throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "UPDATE backups SET verificacion=?, tamano=?, tamano_bytes=? WHERE id=?")) {
                st.setString(1, verificacion);
                st.setString(2, tamano);
                st.setLong(3, tamanoBytes);
                st.setLong(4, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    public List<BackupRecord> listarTodos() throws SQLException {
        return db.leer(c -> {
            List<BackupRecord> resultado = new ArrayList<>();
            try (var st = c.prepareStatement("SELECT * FROM backups ORDER BY id DESC")) {
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        resultado.add(mapear(rs));
                    }
                }
            }
            return resultado;
        });
    }

    public List<BackupRecord> listarPorInstitucion(long institucionId) throws SQLException {
        return db.leer(c -> {
            List<BackupRecord> resultado = new ArrayList<>();
            try (var st = c.prepareStatement(
                    "SELECT * FROM backups WHERE institucion_id=? ORDER BY id DESC")) {
                st.setLong(1, institucionId);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        resultado.add(mapear(rs));
                    }
                }
            }
            return resultado;
        });
    }

    public Optional<BackupRecord> porId(long id) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement("SELECT * FROM backups WHERE id=?")) {
                st.setLong(1, id);
                try (var rs = st.executeQuery()) {
                    return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
                }
            }
        });
    }

    public void eliminar(long id) throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement("DELETE FROM backups WHERE id=?")) {
                st.setLong(1, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    private static BackupRecord mapear(java.sql.ResultSet rs) throws SQLException {
        BackupRecord b = new BackupRecord();
        b.setId(rs.getLong("id"));
        b.setInstitucionId(rs.getLong("institucion_id"));
        b.setInstitucion(rs.getString("institucion"));
        b.setTipo(rs.getString("tipo"));
        b.setIdentificador(rs.getString("identificador"));
        b.setCommitAsociado(rs.getString("commit_asociado"));
        b.setTamano(rs.getString("tamano"));
        b.setTamanoBytes(rs.getLong("tamano_bytes"));
        b.setUbicacion(rs.getString("ubicacion"));
        b.setCreadoEn(rs.getString("creado_en"));
        b.setVerificacion(rs.getString("verificacion"));
        return b;
    }
}
