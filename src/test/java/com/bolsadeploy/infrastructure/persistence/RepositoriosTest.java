package com.bolsadeploy.infrastructure.persistence;

import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.domain.BackupRecord;
import com.bolsadeploy.domain.ConnectionStatus;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.InstitutionStatus;
import com.bolsadeploy.domain.OperationState;
import com.bolsadeploy.domain.VpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración del esquema SQLite local (FASE B): creación del
 * esquema, alta/consulta de VPS e instituciones, checkpoints, ajustes,
 * historial y respaldos sobre una base temporal real.
 */
class RepositoriosTest {

    @TempDir
    Path tmp;

    private Database db;
    private VpsRepository vps;
    private InstitutionRepository instituciones;
    private SettingsRepository ajustes;
    private OperationRepository operaciones;
    private BackupRepository respaldos;

    @BeforeEach
    void abrir() throws SQLException {
        db = new Database(tmp.resolve("test.sqlite"));
        vps = new VpsRepository(db);
        instituciones = new InstitutionRepository(db);
        ajustes = new SettingsRepository(db);
        operaciones = new OperationRepository(db);
        respaldos = new BackupRepository(db);
    }

    @AfterEach
    void cerrar() {
        db.close();
    }

    private static VpsServer vpsEjemplo() {
        VpsServer v = new VpsServer();
        v.setNombre("VPS Demo");
        v.setHost("203.0.113.10");
        v.setPuerto(22);
        v.setUsuario("root");
        v.setTipoAuth(AuthType.PASSWORD);
        v.setDescripcion("servidor de prueba");
        return v;
    }

    private static Institution instEjemplo() {
        Institution i = new Institution();
        i.setNombre("Instituto Demo");
        i.setSiglas("ID");
        i.setDominio("demo.ejemplo.gob.pe");
        i.setSlug("ies-demo");
        i.setNombreBaseDatos("bolsa_ies_demo");
        i.setUsuarioBaseDatos("bolsa_ies_demo_user");
        i.setRama("main");
        i.setEstado(InstitutionStatus.PENDIENTE);
        return i;
    }

    // ------------------------------------------------------------------ VPS
    @Test
    void guardarYLeerVps() throws SQLException {
        long id = vps.guardar(vpsEjemplo());
        assertTrue(id > 0);

        VpsServer leido = vps.porId(id).orElseThrow();
        assertEquals("203.0.113.10", leido.getHost());
        assertEquals(AuthType.PASSWORD, leido.getTipoAuth());
        assertEquals(ConnectionStatus.SIN_COMPROBAR, leido.getEstado());
        assertFalse(leido.getCreadoEn().isBlank());

        List<VpsServer> todos = vps.listar();
        assertEquals(1, todos.size());
        assertEquals("VPS Demo", todos.get(0).getNombre());
    }

    @Test
    void actualizarVpsCambiaEstado() throws SQLException {
        long id = vps.guardar(vpsEjemplo());
        VpsServer v = vps.porId(id).orElseThrow();
        v.setEstado(ConnectionStatus.CONECTADO);
        v.setUltimoResultado("Conectado. Linux ...");
        vps.actualizar(v);

        assertEquals(ConnectionStatus.CONECTADO, vps.porId(id).orElseThrow().getEstado());
        assertEquals("Conectado. Linux ...", vps.porId(id).orElseThrow().getUltimoResultado());
    }

    @Test
    void eliminarVps() throws SQLException {
        long id = vps.guardar(vpsEjemplo());
        vps.eliminar(id);
        assertEquals(Optional.empty(), vps.porId(id));
        assertTrue(vps.listar().isEmpty());
    }

    // -------------------------------------------------------- Instituciones
    @Test
    void guardarInstitucionYPorSlug() throws SQLException {
        long vpsId = vps.guardar(vpsEjemplo());
        Institution i = instEjemplo();
        i.setVpsId(vpsId);
        long id = instituciones.guardar(i);
        assertTrue(id > 0);

        Institution leida = instituciones.porSlug("ies-demo").orElseThrow();
        assertEquals(id, leida.getId());
        assertEquals(vpsId, leida.getVpsId());
        assertEquals(InstitutionStatus.PENDIENTE, leida.getEstado());
    }

    @Test
    void dominioEnUsoDetectaDuplicado() throws SQLException {
        long vpsId = vps.guardar(vpsEjemplo());
        Institution i = instEjemplo();
        i.setVpsId(vpsId);
        long id = instituciones.guardar(i);

        assertTrue(instituciones.dominioEnUso("demo.ejemplo.gob.pe", 0));
        assertFalse(instituciones.dominioEnUso("demo.ejemplo.gob.pe", id)); // la misma fila se excluye
        assertFalse(instituciones.dominioEnUso("otro.ejemplo.gob.pe", 0));
    }

    @Test
    void checkpointSeActualiza() throws SQLException {
        long vpsId = vps.guardar(vpsEjemplo());
        Institution i = instEjemplo();
        i.setVpsId(vpsId);
        long id = instituciones.guardar(i);

        instituciones.guardarCheckpoint(id, "migracion");
        assertEquals("migracion", instituciones.porId(id).orElseThrow().getCheckpoint());

        instituciones.guardarCheckpoint(id, "");
        assertEquals("", instituciones.porId(id).orElseThrow().getCheckpoint());
    }

    @Test
    void institucionNoPuedeQuedarSinVps() {
        Institution i = instEjemplo();
        i.setVpsId(9999); // inexistente
        // La FK debe impedirlo (PRAGMA foreign_keys = ON)
        assertThrows(SQLException.class, () -> instituciones.guardar(i));
    }

    // --------------------------------------------------------------- Ajustes
    @Test
    void ajustesPersistenYVuelvenALosValoresPorDefecto() throws SQLException {
        AppSettings s = ajustes.cargar(); // vacío → por defecto
        assertEquals(AppSettings.TEMA_OSCURO, s.getTema());
        assertEquals("Dontoti02/bolsalaboralv2", s.getRepoGitHub());
        assertEquals(2, s.getConcurrenciaActualizaciones());
        assertTrue(s.isRespaldoPreActualizacion());
        assertTrue(s.isVerificarHostKeys());

        s.setTema(AppSettings.TEMA_CLARO);
        s.setConcurrenciaActualizaciones(4);
        s.setRepoGitHub("otro/repo");
        s.setRespaldoPreActualizacion(false);
        ajustes.guardar(s);

        AppSettings leido = ajustes.cargar();
        assertEquals(AppSettings.TEMA_CLARO, leido.getTema());
        assertEquals(4, leido.getConcurrenciaActualizaciones());
        assertEquals("otro/repo", leido.getRepoGitHub());
        assertFalse(leido.isRespaldoPreActualizacion());
    }

    @Test
    void ajustesGuardadosDosVecesNoDuplicanFilas() throws SQLException {
        AppSettings s = ajustes.cargar();
        ajustes.guardar(s);
        ajustes.guardar(s);
        int filas = db.leer(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM settings")) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertEquals(11, filas, "cada clave debe existir una sola vez (upsert)");
    }

    // ----------------------------------------------------------- Historial
    @Test
    void operacionAbrirYCerrar() throws SQLException {
        long id = operaciones.abrir("DESPLIEGUE", "Instituto Demo", "203.0.113.10",
                "Instalación en demo.ejemplo.gob.pe");
        assertTrue(id > 0);
        assertEquals("EN_CURSO", operaciones.porId(id).orElseThrow().getEstado());

        operaciones.cerrar(id, OperationState.EXITOSA.name(), "validacion=EXITOSA");
        var op = operaciones.porId(id).orElseThrow();
        assertEquals("EXITOSA", op.getEstado());
        assertFalse(op.getFinEn().isBlank());
        assertTrue(op.getTecnico().contains("validacion"));
    }

    @Test
    void filtrarHistorialPorTipoYEstado() throws SQLException {
        long a = operaciones.abrir("RESPALDO", "A", "-", "respaldo A");
        operaciones.cerrar(a, OperationState.EXITOSA.name(), "");
        long b = operaciones.abrir("ACTUALIZACION", "B", "-", "actualización B");
        operaciones.cerrar(b, OperationState.FALLIDA.name(), "error real");
        long c = operaciones.abrir("RESPALDO", "C", "-", "respaldo C");
        operaciones.cerrar(c, OperationState.FALLIDA.name(), "");

        assertEquals(3, operaciones.listar(10).size());
        assertEquals(2, operaciones.filtrar("RESPALDO", null, 10).size());
        assertEquals(2, operaciones.filtrar(null, "FALLIDA", 10).size());
        assertEquals(1, operaciones.filtrar("ACTUALIZACION", "FALLIDA", 10).size());
        assertEquals(1, operaciones.listar(1).size(), "el límite se respeta");
    }

    // ------------------------------------------------------------ Respaldos
    @Test
    void respaldoSeRegistraYConsulta() throws SQLException {
        BackupRecord b = new BackupRecord();
        b.setInstitucionId(1);
        b.setInstitucion("Instituto Demo");
        b.setTipo("INTEGRAL");
        b.setIdentificador("20260101-120000");
        b.setUbicacion("/var/www/bolsadeploy/ies-demo/backups/bolsa-ies-demo.tar.gz");
        b.setTamano("12.5 MB");
        b.setTamanoBytes(13107200);
        b.setVerificacion("OK");
        b.setCreadoEn("2026-01-01 12:00:00");

        long id = respaldos.guardar(b);
        assertTrue(id > 0);

        var listado = respaldos.listarPorInstitucion(1);
        assertEquals(1, listado.size());
        assertEquals("OK", listado.get(0).getVerificacion());
        assertEquals(13107200, listado.get(0).getTamanoBytes());

        respaldos.actualizarVerificacion(id, "FALLIDA", "dump incompleto", 0);
        BackupRecord leido = respaldos.porId(id).orElseThrow();
        assertEquals("FALLIDA", leido.getVerificacion());

        respaldos.eliminar(id);
        assertTrue(respaldos.listarPorInstitucion(1).isEmpty());
    }
}
