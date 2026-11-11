package com.bolsadeploy.application;

import com.bolsadeploy.backups.BackupService;
import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.DeploymentListener;
import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.github.GitHubClient;
import com.bolsadeploy.infrastructure.persistence.BackupRepository;
import com.bolsadeploy.infrastructure.persistence.Database;
import com.bolsadeploy.infrastructure.persistence.InstitutionRepository;
import com.bolsadeploy.infrastructure.persistence.OperationRepository;
import com.bolsadeploy.infrastructure.persistence.SettingsRepository;
import com.bolsadeploy.infrastructure.persistence.VpsRepository;
import com.bolsadeploy.infrastructure.ssh.HostKeyPrompt;
import com.bolsadeploy.infrastructure.ssh.HostKeyStore;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.infrastructure.ssh.SshException;
import com.bolsadeploy.logging.AuditLog;
import com.bolsadeploy.monitoring.MonitorService;
import com.bolsadeploy.security.CredentialStore;
import com.bolsadeploy.security.FileCredentialStore;
import com.bolsadeploy.updates.UpdateExecutor;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Raíz de composición de la aplicación: abre SQLite, los repositorios, el
 * almacén de credenciales cifrado, las host keys, el cliente de GitHub y los
 * servicios. Un solo punto de verdad para la UI.
 *
 * Datos en {@code %APPDATA%\BolsaDeployManager} (o {@code ~/.bolsadeploy}):
 * {@code bolsadeploy.sqlite}, {@code credenciales.properties},
 * {@code known_hosts}.
 */
public final class AppContext implements AutoCloseable {

    public final Database db;
    public final VpsRepository vps;
    public final InstitutionRepository instituciones;
    public final OperationRepository operaciones;
    public final BackupRepository respaldos;
    public final SettingsRepository ajustesRepo;
    public final CredentialStore credenciales;
    public final HostKeyStore hostKeys;
    public final GitHubClient github;
    public final AuditLog auditoria;
    public final MonitorService monitoreo;
    public final BackupService backups;
    public final UpdateExecutor actualizaciones;
    public final com.bolsadeploy.database.DatabaseManagerService databaseManager;

    /** Preferencias vigentes (recargar tras guardarlas en Configuración). */
    public AppSettings ajustes;

    private AppContext(Database db) throws SQLException {
        this.db = db;
        this.vps = new VpsRepository(db);
        this.instituciones = new InstitutionRepository(db);
        this.operaciones = new OperationRepository(db);
        this.respaldos = new BackupRepository(db);
        this.ajustesRepo = new SettingsRepository(db);
        this.credenciales = FileCredentialStore.porDefecto();
        this.hostKeys = new HostKeyStore(carpetaDatos().resolve("known_hosts"));
        this.github = new GitHubClient();
        this.auditoria = new AuditLog(operaciones);
        this.monitoreo = new MonitorService();
        this.backups = new BackupService();
        this.actualizaciones = new UpdateExecutor();
        this.databaseManager = new com.bolsadeploy.database.DatabaseManagerService(this);
        this.ajustes = ajustesRepo.cargar();
        this.github.setToken(ajustes.getGithubToken());
    }

    public static AppContext abrir() throws SQLException {
        return new AppContext(Database.abrirPorDefecto());
    }

    /** Solo para pruebas: base de datos en una ruta concreta. */
    public static AppContext sobre(Database db) throws SQLException {
        return new AppContext(db);
    }

    // ------------------------------------------------------------------
    // Rutas
    // ------------------------------------------------------------------

    public static Path carpetaDatos() {
        return Database.resolverDirectorioDatos();
    }

    /** Carpeta local donde se descargan los respaldos. */
    public Path carpetaRespaldosLocal() {
        String c = ajustes.getCarpetaRespaldos();
        return c != null && !c.isBlank() ? Path.of(c) : carpetaDatos().resolve("respaldos");
    }

    /** Vuelve a leer ajustes del disco (y actualiza el token de GitHub). */
    public void recargarAjustes() throws SQLException {
        this.ajustes = ajustesRepo.cargar();
        this.github.setToken(ajustes.getGithubToken());
    }

    // ------------------------------------------------------------------
    // Conexión SSH
    // ------------------------------------------------------------------

    /**
     * Conecta con un VPS resolviendo su secreto del almacén cifrado.
     *
     * @param prompt diálogo de aprobación de host key (nunca auto-aprueba)
     */
    public SshConnection conectar(VpsServer servidor, HostKeyPrompt prompt) throws SshException {
        String secreto = credenciales.obtener(servidor.credencialId()).orElse("");
        if (secreto.isBlank()) {
            throw new SshException(SshException.Codigo.AUTENTICACION_RECHAZADA,
                    "No hay credencial guardada para '" + servidor.getNombre()
                            + "'. Edite el servidor y vuelva a ingresar la contraseña o clave.");
        }
        SshConnection.Params params = new SshConnection.Params(
                servidor.getHost(),
                servidor.getPuerto() > 0 ? servidor.getPuerto() : ajustes.getPuertoSshDefault(),
                servidor.getUsuario(),
                servidor.getTipoAuth(),
                secreto,
                ajustes.getTimeoutSshSeg(),
                ajustes.isVerificarHostKeys());
        return SshConnection.conectar(params, hostKeys, prompt);
    }

    /** Contexto de despliegue para una institución (ya conectado el SSH). */
    public DeploymentContext contexto(VpsServer servidor, Institution inst, SshConnection ssh,
                                      DeploymentListener listener) {
        DeploymentContext ctx = new DeploymentContext(servidor, inst, ajustes, ssh, listener);
        try {
            credenciales.obtener(inst.credencialDbId()).ifPresent(p -> {
                if (!p.isBlank()) ctx.dbPassword = p;
            });
        } catch (Exception ignored) {
        }
        return ctx;
    }

    /** Busca el VPS de una institución (debe estar registrado). */
    public VpsServer vpsDe(Institution inst) throws SQLException {
        return vps.porId(inst.getVpsId())
                .orElseThrow(() -> new IllegalStateException(
                        "La institución '" + inst.getNombre()
                                + "' no tiene un VPS asociado (revíselo en Instituciones)."));
    }

    @Override
    public void close() {
        try {
            db.close();
        } catch (Exception e) {
            // el cierre no debe bloquear la salida
        }
    }
}
