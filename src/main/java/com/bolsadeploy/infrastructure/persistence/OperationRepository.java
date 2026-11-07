package com.bolsadeploy.infrastructure.persistence;

import com.bolsadeploy.domain.OperationRecord;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Repositorio de historial de operaciones. */
public class OperationRepository {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Database db;

    public OperationRepository(Database db) {
        this.db = db;
    }

    /** Abre una operación y devuelve su id. */
    public long abrir(String tipo, String institucion, String servidor, String detalle) throws SQLException {
        String ahora = LocalDateTime.now().format(FECHA);
        return db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "INSERT INTO operations (tipo, institucion, servidor, detalle, estado, inicio_en)"
                            + " VALUES (?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                st.setString(1, tipo);
                st.setString(2, institucion == null ? "" : institucion);
                st.setString(3, servidor == null ? "" : servidor);
                st.setString(4, detalle == null ? "" : detalle);
                st.setString(5, "EN_CURSO");
                st.setString(6, ahora);
                st.executeUpdate();
                try (var keys = st.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Cierra una operación con su estado final y salida técnica resumida. */
    public void cerrar(long id, String estado, String tecnico) throws SQLException {
        String ahora = LocalDateTime.now().format(FECHA);
        db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "UPDATE operations SET estado=?, fin_en=?, tecnico=? WHERE id=?")) {
                st.setString(1, estado);
                st.setString(2, ahora);
                st.setString(3, recortar(tecnico, 4000));
                st.setLong(4, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    public java.util.Optional<OperationRecord> porId(long id) throws SQLException {
        return db.leer(c -> {
            try (var st = c.prepareStatement("SELECT * FROM operations WHERE id=?")) {
                st.setLong(1, id);
                try (var rs = st.executeQuery()) {
                    if (rs.next()) {
                        OperationRecord o = new OperationRecord();
                        o.setId(rs.getLong("id"));
                        o.setTipo(rs.getString("tipo"));
                        o.setInstitucion(rs.getString("institucion"));
                        o.setServidor(rs.getString("servidor"));
                        o.setDetalle(rs.getString("detalle"));
                        o.setEstado(rs.getString("estado"));
                        o.setInicioEn(rs.getString("inicio_en"));
                        o.setFinEn(rs.getString("fin_en"));
                        o.setTecnico(rs.getString("tecnico"));
                        return java.util.Optional.of(o);
                    }
                    return java.util.Optional.empty();
                }
            }
        });
    }

    public List<OperationRecord> listar(int limite) throws SQLException {        return db.leer(c -> {
            List<OperationRecord> resultado = new ArrayList<>();
            try (var st = c.prepareStatement(
                    "SELECT * FROM operations ORDER BY id DESC LIMIT ?")) {
                st.setInt(1, limite);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        OperationRecord o = new OperationRecord();
                        o.setId(rs.getLong("id"));
                        o.setTipo(rs.getString("tipo"));
                        o.setInstitucion(rs.getString("institucion"));
                        o.setServidor(rs.getString("servidor"));
                        o.setDetalle(rs.getString("detalle"));
                        o.setEstado(rs.getString("estado"));
                        o.setInicioEn(rs.getString("inicio_en"));
                        o.setFinEn(rs.getString("fin_en"));
                        o.setTecnico(rs.getString("tecnico"));
                        resultado.add(o);
                    }
                }
            }
            return resultado;
        });
    }

    /** Filtrado por tipo y/o estado (para Historial y Registros). */
    public List<OperationRecord> filtrar(String tipo, String estado, int limite) throws SQLException {
        return db.leer(c -> {
            List<OperationRecord> resultado = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM operations WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (tipo != null && !tipo.isBlank()) {
                sql.append(" AND tipo=?");
                params.add(tipo);
            }
            if (estado != null && !estado.isBlank()) {
                sql.append(" AND estado=?");
                params.add(estado);
            }
            sql.append(" ORDER BY id DESC LIMIT ?");
            params.add(limite);
            try (var st = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    st.setObject(i + 1, params.get(i));
                }
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        OperationRecord o = new OperationRecord();
                        o.setId(rs.getLong("id"));
                        o.setTipo(rs.getString("tipo"));
                        o.setInstitucion(rs.getString("institucion"));
                        o.setServidor(rs.getString("servidor"));
                        o.setDetalle(rs.getString("detalle"));
                        o.setEstado(rs.getString("estado"));
                        o.setInicioEn(rs.getString("inicio_en"));
                        o.setFinEn(rs.getString("fin_en"));
                        o.setTecnico(rs.getString("tecnico"));
                        resultado.add(o);
                    }
                }
            }
            return resultado;
        });
    }

    private static String recortar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
