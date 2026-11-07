package com.bolsadeploy.infrastructure.persistence;

import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.domain.ConnectionStatus;
import com.bolsadeploy.domain.VpsServer;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositorio de servidores VPS. */
public class VpsRepository {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Database db;

    public VpsRepository(Database db) {
        this.db = db;
    }

    public List<VpsServer> listar() throws SQLException {
        return db.leer(c -> {
            List<VpsServer> resultado = new ArrayList<>();
            try (var st = c.prepareStatement("SELECT * FROM vps_servers ORDER BY nombre");
                 var rs = st.executeQuery()) {
                while (rs.next()) {
                    resultado.add(mapear(rs));
                }
            }
            return resultado;
        });
    }

    public Optional<VpsServer> porId(long id) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement("SELECT * FROM vps_servers WHERE id = ?")) {
                st.setLong(1, id);
                try (var rs = st.executeQuery()) {
                    return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Guarda (inserta si id=0) y devuelve el id. */
    public long guardar(VpsServer v) throws SQLException {
        if (v.getCreadoEn() == null || v.getCreadoEn().isBlank()) {
            v.setCreadoEn(LocalDateTime.now().format(FECHA));
        }
        return db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "INSERT INTO vps_servers (nombre, host, puerto, usuario, tipo_auth, descripcion, grupo, estado,"
                            + " ultimo_resultado, ultimo_check_en, creado_en)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                preparar(st, v, 10);
                st.setString(11, v.getCreadoEn());
                st.executeUpdate();
                try (var keys = st.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    v.setId(id);
                    return id;
                }
            }
        });
    }

    public void actualizar(VpsServer v) throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "UPDATE vps_servers SET nombre=?, host=?, puerto=?, usuario=?, tipo_auth=?, descripcion=?,"
                            + " grupo=?, estado=?, ultimo_resultado=?, ultimo_check_en=? WHERE id=?")) {
                preparar(st, v, 10);
                st.setLong(11, v.getId());
                st.executeUpdate();
            }
            return null;
        });
    }

    public void eliminar(long id) throws SQLException {
        db.escribir(c -> {
            try (var st = c.prepareStatement("DELETE FROM vps_servers WHERE id=?")) {
                st.setLong(1, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    /** Instituciones que usan este VPS (para bloquear borrados peligrosos). */
    public int institucionesAsociadas(long vpsId) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement("SELECT COUNT(*) FROM institutions WHERE vps_id=?")) {
                st.setLong(1, vpsId);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /** Parámetros 1..n de las columnas comunes de escritura. */
    private static void preparar(java.sql.PreparedStatement st, VpsServer v, int hasta) throws SQLException {
        st.setString(1, v.getNombre());
        st.setString(2, v.getHost());
        st.setInt(3, v.getPuerto());
        st.setString(4, v.getUsuario());
        st.setString(5, v.getTipoAuth().name());
        st.setString(6, v.getDescripcion() == null ? "" : v.getDescripcion());
        st.setString(7, v.getGrupo() == null ? "" : v.getGrupo());
        st.setString(8, v.getEstado().name());
        st.setString(9, v.getUltimoResultado() == null ? "" : v.getUltimoResultado());
        st.setString(10, v.getUltimoCheckEn() == null ? "" : v.getUltimoCheckEn());
        if (hasta != 10) {
            throw new IllegalStateException("preparar solo soporta 10 parámetros");
        }
    }

    private static VpsServer mapear(java.sql.ResultSet rs) throws SQLException {
        VpsServer v = new VpsServer();
        v.setId(rs.getLong("id"));
        v.setNombre(rs.getString("nombre"));
        v.setHost(rs.getString("host"));
        v.setPuerto(rs.getInt("puerto"));
        v.setUsuario(rs.getString("usuario"));
        v.setTipoAuth(AuthType.valueOf(rs.getString("tipo_auth")));
        v.setDescripcion(rs.getString("descripcion"));
        v.setGrupo(rs.getString("grupo"));
        v.setEstado(ConnectionStatus.valueOf(rs.getString("estado")));
        v.setUltimoResultado(rs.getString("ultimo_resultado"));
        v.setUltimoCheckEn(rs.getString("ultimo_check_en"));
        v.setCreadoEn(rs.getString("creado_en"));
        return v;
    }
}
