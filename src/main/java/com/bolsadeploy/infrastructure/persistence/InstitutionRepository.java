package com.bolsadeploy.infrastructure.persistence;

import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.InstitutionStatus;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositorio de instituciones. */
public class InstitutionRepository {

    private static final String COLUMNAS = """
            id, nombre, siglas, codigo_interno, region, dominio, usa_www, email_contacto, vps_id,
            ruta_instalacion, slug, repo_git, nombre_base_datos, usuario_base_datos, rama, email_ssl,
            https_activado, smtp_host, smtp_puerto, smtp_usuario, smtp_from, estado,
            fecha_instalacion, ultima_actualizacion, commit_desplegado, ultimo_error, checkpoint""";

    private final Database db;

    public InstitutionRepository(Database db) {
        this.db = db;
    }

    public List<Institution> listar() throws SQLException {
        return db.leer(c -> {
            List<Institution> resultado = new ArrayList<>();
            try (var st = c.prepareStatement("SELECT " + COLUMNAS + " FROM institutions ORDER BY nombre");
                 var rs = st.executeQuery()) {
                while (rs.next()) {
                    resultado.add(mapear(rs));
                }
            }
            return resultado;
        });
    }

    public Optional<Institution> porId(long id) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement("SELECT " + COLUMNAS + " FROM institutions WHERE id=?")) {
                st.setLong(1, id);
                try (var rs = st.executeQuery()) {
                    return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Institution> porSlug(String slug) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement("SELECT " + COLUMNAS + " FROM institutions WHERE slug=?")) {
                st.setString(1, slug);
                try (var rs = st.executeQuery()) {
                    return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
                }
            }
        });
    }

    /** true si otra institución (distinta de id) ya usa ese dominio. */
    public boolean dominioEnUso(String dominio, long excluirId) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement(
                    "SELECT COUNT(*) FROM institutions WHERE dominio=? AND id<>?")) {
                st.setString(1, dominio);
                st.setLong(2, excluirId);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            }
        });
    }

    public long guardar(Institution i) throws SQLException {
        return db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "INSERT INTO institutions (nombre, siglas, codigo_interno, region, dominio, usa_www,"
                            + " email_contacto, vps_id, ruta_instalacion, slug, repo_git, nombre_base_datos, usuario_base_datos,"
                            + " rama, email_ssl, https_activado, smtp_host, smtp_puerto, smtp_usuario, smtp_from,"
                            + " estado, fecha_instalacion, ultima_actualizacion, commit_desplegado, ultimo_error,"
                            + " checkpoint) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                preparar(st, i);
                st.executeUpdate();
                try (var keys = st.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    i.setId(id);
                    return id;
                }
            }
        });
    }

    public void actualizar(Institution i) throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "UPDATE institutions SET nombre=?, siglas=?, codigo_interno=?, region=?, dominio=?, usa_www=?,"
                            + " email_contacto=?, vps_id=?, ruta_instalacion=?, slug=?, repo_git=?, nombre_base_datos=?,"
                            + " usuario_base_datos=?, rama=?, email_ssl=?, https_activado=?, smtp_host=?,"
                            + " smtp_puerto=?, smtp_usuario=?, smtp_from=?, estado=?, fecha_instalacion=?,"
                            + " ultima_actualizacion=?, commit_desplegado=?, ultimo_error=?, checkpoint=?"
                            + " WHERE id=?")) {
                preparar(st, i);
                st.setLong(27, i.getId());
                st.executeUpdate();
            }
            return null;
        });
    }

    public void eliminar(long id) throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement("DELETE FROM institutions WHERE id=?")) {
                st.setLong(1, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    /** Actualiza solo el checkpoint (retomada tras interrupción). */
    public void guardarCheckpoint(long id, String etapa) throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement("UPDATE institutions SET checkpoint=? WHERE id=?")) {
                st.setString(1, etapa);
                st.setLong(2, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    private static void preparar(java.sql.PreparedStatement st, Institution i) throws SQLException {
        st.setString(1, i.getNombre());
        st.setString(2, nulo(i.getSiglas()));
        st.setString(3, nulo(i.getCodigoInterno()));
        st.setString(4, nulo(i.getRegion()));
        st.setString(5, nulo(i.getDominio()));
        st.setInt(6, i.isUsaWww() ? 1 : 0);
        st.setString(7, nulo(i.getEmailContacto()));
        st.setLong(8, i.getVpsId());
        st.setString(9, nulo(i.getRutaInstalacion()));
        st.setString(10, nulo(i.getSlug()));
        st.setString(11, nulo(i.getRepoGit()));
        st.setString(12, nulo(i.getNombreBaseDatos()));
        st.setString(13, nulo(i.getUsuarioBaseDatos()));
        st.setString(14, nulo(i.getRama()));
        st.setString(15, nulo(i.getEmailSsl()));
        st.setInt(16, i.isHttpsActivado() ? 1 : 0);
        st.setString(17, nulo(i.getSmtpHost()));
        st.setInt(18, i.getSmtpPuerto());
        st.setString(19, nulo(i.getSmtpUsuario()));
        st.setString(20, nulo(i.getSmtpFrom()));
        st.setString(21, i.getEstado().name());
        st.setString(22, nulo(i.getFechaInstalacion()));
        st.setString(23, nulo(i.getUltimaActualizacion()));
        st.setString(24, nulo(i.getCommitDesplegado()));
        st.setString(25, nulo(i.getUltimoError()));
        st.setString(26, i.getCheckpoint() == null ? "" : i.getCheckpoint());
    }

    private static String nulo(String s) {
        return s == null ? "" : s;
    }

    private static Institution mapear(java.sql.ResultSet rs) throws SQLException {
        Institution i = new Institution();
        i.setId(rs.getLong("id"));
        i.setNombre(rs.getString("nombre"));
        i.setSiglas(rs.getString("siglas"));
        i.setCodigoInterno(rs.getString("codigo_interno"));
        i.setRegion(rs.getString("region"));
        i.setDominio(rs.getString("dominio"));
        i.setUsaWww(rs.getInt("usa_www") == 1);
        i.setEmailContacto(rs.getString("email_contacto"));
        i.setVpsId(rs.getLong("vps_id"));
        i.setRutaInstalacion(rs.getString("ruta_instalacion"));
        i.setSlug(rs.getString("slug"));
        try {
            i.setRepoGit(rs.getString("repo_git"));
        } catch (SQLException ignored) {
            i.setRepoGit("");
        }
        i.setNombreBaseDatos(rs.getString("nombre_base_datos"));
        i.setUsuarioBaseDatos(rs.getString("usuario_base_datos"));
        i.setRama(rs.getString("rama"));
        i.setEmailSsl(rs.getString("email_ssl"));
        i.setHttpsActivado(rs.getInt("https_activado") == 1);
        i.setSmtpHost(rs.getString("smtp_host"));
        i.setSmtpPuerto(rs.getInt("smtp_puerto"));
        i.setSmtpUsuario(rs.getString("smtp_usuario"));
        i.setSmtpFrom(rs.getString("smtp_from"));
        i.setEstado(InstitutionStatus.valueOf(rs.getString("estado")));
        i.setFechaInstalacion(rs.getString("fecha_instalacion"));
        i.setUltimaActualizacion(rs.getString("ultima_actualizacion"));
        i.setCommitDesplegado(rs.getString("commit_desplegado"));
        i.setUltimoError(rs.getString("ultimo_error"));
        i.setCheckpoint(rs.getString("checkpoint"));
        return i;
    }
}
