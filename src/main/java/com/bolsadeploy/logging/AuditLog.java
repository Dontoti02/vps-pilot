package com.bolsadeploy.logging;

import com.bolsadeploy.domain.OperationRecord;
import com.bolsadeploy.domain.OperationState;
import com.bolsadeploy.infrastructure.persistence.OperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bitácora de auditoría (FASE G): cada operación relevante queda en SQLite
 * (Historial/Registros) y en el log de la aplicación, con el mismo criterio:
 *
 * <ul>
 *   <li>Nunca se guarda un secreto: la salida técnica pasa por
 *       {@link #sinSecretos(String)} antes de persistirse.</li>
 *   <li>Un error de auditoría no rompe la operación principal (se avisa por
 *       log), pero tampoco se silencia.</li>
 * </ul>
 */
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    /** Frases que jamás deben quedar en el historial. */
    private static final Pattern SECRETO = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|authorization)\\s*[=:]\\s*\\S+");

    private final OperationRepository repo;

    public AuditLog(OperationRepository repo) {
        this.repo = repo;
    }

    /** Abre una operación y devuelve su id (0 si falla la auditoría). */
    public long abrir(String tipo, String institucion, String servidor, String detalle) {
        try {
            long id = repo.abrir(tipo, institucion, servidor, sinSecretos(detalle));
            log.info("AUDITORIA INICIO id={} tipo={} inst={} srv={} :: {}", id, tipo, institucion,
                    servidor, recortar(detalle));
            return id;
        } catch (SQLException e) {
            log.error("No se pudo registrar el inicio de la operación {}: {}", tipo, e.getMessage());
            return 0;
        }
    }

    /** Cierra la operación con su estado final. */
    public void cerrar(long id, OperationState estado, String tecnico) {
        if (id <= 0) {
            return;
        }
        try {
            String limpio = sinSecretos(tecnico);
            repo.cerrar(id, estado.name(), limpio);
            log.info("AUDITORIA FIN id={} estado={} :: {}", id, estado, recortar(limpio));
        } catch (SQLException e) {
            log.error("No se pudo cerrar la operación {}: {}", id, e.getMessage());
        }
    }

    /** Registra una operación completa de un solo paso. */
    public void registrar(String tipo, String institucion, String servidor, String detalle,
                          OperationState estado, String tecnico) {
        long id = abrir(tipo, institucion, servidor, detalle);
        if (id > 0) {
            cerrar(id, estado, tecnico);
        }
    }

    public List<OperationRecord> listar(int limite) throws SQLException {
        return repo.listar(limite);
    }

    public List<OperationRecord> filtrar(String tipo, String estado, int limite) throws SQLException {
        return repo.filtrar(tipo, estado, limite);
    }

    // ------------------------------------------------------------------

    /** Sustituye valores de claves sensibles por <REDACTED>. */
    public static String sinSecretos(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        return SECRETO.matcher(texto).replaceAll(m ->
                m.group(1) + "=" + "<REDACTED>");
    }

    private static String recortar(String s) {
        if (s == null) {
            return "";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }
}
