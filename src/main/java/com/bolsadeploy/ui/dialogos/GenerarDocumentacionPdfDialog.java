package com.bolsadeploy.ui.dialogos;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.reports.DocumentacionPdfGenerator;
import com.bolsadeploy.reports.DocumentacionPdfGenerator.DatosDocumentacion;
import com.bolsadeploy.reports.VpsDocumentationHelper;
import com.bolsadeploy.reports.VpsDocumentationHelper.DatosVpsDetectados;
import com.bolsadeploy.reports.VpsDocumentationHelper.ResultadoAdminVps;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Diálogo amigable y prellenado para generar el PDF de documentación técnica,
 * enlaces, credenciales de administrador y configuración paso a paso de Navicat.
 *
 * <p>Permite además sincronizar en 1 clic los datos reales desde el VPS (HTTPS,
 * {@code shared/.env} y correo real del administrador en BD) y generar/aplicar
 * una contraseña funcional del administrador directamente en el VPS para que el
 * cliente pueda ingresar de inmediato con los datos exactos del PDF.
 */
public class GenerarDocumentacionPdfDialog extends Dialog<Void> {

    private static final Logger log = LoggerFactory.getLogger(GenerarDocumentacionPdfDialog.class);

    private final Servicios servicios;
    private final Institution inst;
    private final VpsServer vps;

    // Botón maestro de autodetección
    private Button btnDetectarVps;
    private Label lblEstadoVps;

    // Enlaces
    private TextField txtUrlWeb;
    private TextField txtUrlLogin;
    private TextField txtUrlAdmin;

    // Credenciales Admin
    private TextField txtAdminEmail;
    private PasswordField pwdAdmin;
    private TextField txtAdminVisible;
    private ToggleButton btnOjoAdmin;
    private Button btnAplicarAdminVps;
    private Label lblEstadoAdmin;
    private CheckBox chkIncluirAdminPass;
    private CheckBox chkRecordarAdmin;

    // BD + Navicat
    private ComboBox<String> cbMotorBd;
    private TextField txtPuertoBd;
    private TextField txtNombreBd;
    private TextField txtUsuarioBd;
    private PasswordField pwdBd;
    private TextField txtBdVisible;
    private CheckBox chkIncluirBdPass;
    private CheckBox chkIncluirSshSecret;
    private Label lblEstadoEnv;

    // Notas y salida
    private TextArea txtNotas;
    private CheckBox chkAbrirAlGuardar;

    private String sshSecretoGuardado = "";

    public GenerarDocumentacionPdfDialog(Servicios servicios, Institution inst, VpsServer vps) {
        this(servicios, inst, vps, null, null, null);
    }

    public GenerarDocumentacionPdfDialog(Servicios servicios, Institution inst, VpsServer vps,
                                         String adminEmailInicial, String adminPassInicial, String dbPassInicial) {
        this.servicios = servicios;
        this.inst = inst;
        this.vps = vps;

        setTitle("Generar Documentación PDF — " + inst.getNombre());
        setHeaderText(null);
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().add("dialogo-moderno");
        pane.setPrefSize(720, 680);

        cargarSecretosLocales();
        construirContenido(pane, adminEmailInicial, adminPassInicial, dbPassInicial);

        pane.getButtonTypes().add(ButtonType.CLOSE);
        var closeBtn = pane.lookupButton(ButtonType.CLOSE);
        if (closeBtn != null) {
            closeBtn.setVisible(false);
            closeBtn.setManaged(false);
        }
    }

    private void cargarSecretosLocales() {
        if (vps != null) {
            try {
                sshSecretoGuardado = servicios.app().credenciales.obtener(vps.credencialId()).orElse("");
            } catch (Exception ignored) {
            }
        }
    }

    private void construirContenido(DialogPane pane, String adminEmailInicial, String adminPassInicial, String dbPassInicial) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        // Encabezado + botón maestro de autodetección desde VPS
        VBox header = new VBox(8);
        header.getStyleClass().add("card");
        header.setPadding(new Insets(12));
        header.setStyle("-fx-border-color: #4f46e5; -fx-border-width: 0 0 0 4px;");

        Label lblTit = new Label("Documentación PDF de Entrega — " + inst.getNombre());
        lblTit.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label lblSub = new Label("Revisa o completa los datos antes de guardar. El PDF incluirá enlaces clicables, "
                + "credenciales de ingreso del administrador para tu cliente y la guía paso a paso de Navicat (General + Túnel SSH).");
        lblSub.setWrapText(true);
        lblSub.getStyleClass().add("seccion-descripcion");

        btnDetectarVps = new Button("🔄 Detectar Datos del VPS (HTTPS, .env y Correo Admin)");
        btnDetectarVps.getStyleClass().add("boton-primario");
        btnDetectarVps.setDisable(vps == null);
        btnDetectarVps.setOnAction(ev -> detectarTodoDesdeVps());

        lblEstadoVps = new Label(vps == null
                ? "Esta institución no tiene un VPS asociado."
                : "Pulsa para leer automáticamente HTTPS, BD y el correo del admin actual desde " + vps.getHost());
        lblEstadoVps.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        lblEstadoVps.setWrapText(true);

        HBox filaDetectar = new HBox(10, btnDetectarVps, lblEstadoVps);
        filaDetectar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lblEstadoVps, Priority.ALWAYS);

        header.getChildren().addAll(lblTit, lblSub, filaDetectar);

        // ==========================================================
        // 1. Enlaces del sistema
        // ==========================================================
        String baseUrl = inst.urlBase();
        txtUrlWeb = new TextField(baseUrl);
        txtUrlLogin = new TextField(baseUrl.replaceAll("/+$", "") + "/login");
        txtUrlAdmin = new TextField(baseUrl.replaceAll("/+$", "") + "/admin/dashboard");

        GridPane gridLinks = crearGrid();
        gridLinks.add(new Label("Sitio Web Principal:"), 0, 0);
        gridLinks.add(txtUrlWeb, 1, 0);
        gridLinks.add(new Label("Inicio de Sesión (Login):"), 0, 1);
        gridLinks.add(txtUrlLogin, 1, 1);
        gridLinks.add(new Label("Panel de Administración:"), 0, 2);
        gridLinks.add(txtUrlAdmin, 1, 2);
        VBox secLinks = crearCardSeccion("1. Enlaces del Sistema (clicables en el PDF)", gridLinks);

        // ==========================================================
        // 2. Credenciales Admin Web (con generador y aplicación directa al VPS)
        // ==========================================================
        String emailSaved = (adminEmailInicial != null && !adminEmailInicial.isBlank())
                ? adminEmailInicial
                : leerCredencial(inst.credencialAdminEmailId());
        if (emailSaved.isBlank() && inst.getEmailContacto() != null && !inst.getEmailContacto().isBlank()) {
            emailSaved = inst.getEmailContacto();
        }
        String passAdminSaved = (adminPassInicial != null && !adminPassInicial.isBlank())
                ? adminPassInicial
                : leerCredencial(inst.credencialAdminPassId());

        txtAdminEmail = new TextField(emailSaved);
        txtAdminEmail.setPromptText("ej. admin@" + (inst.dominioPrincipal() != null ? inst.dominioPrincipal() : "instituto.edu.pe"));

        pwdAdmin = new PasswordField();
        pwdAdmin.setText(passAdminSaved);
        pwdAdmin.setPromptText("Escribe la clave actual o genera una nueva para el cliente");
        txtAdminVisible = new TextField(passAdminSaved);
        txtAdminVisible.setPromptText("Escribe la clave actual o genera una nueva para el cliente");

        btnOjoAdmin = new ToggleButton("👁");
        Button btnGenPass = new Button("🎲");
        btnGenPass.setTooltip(new Tooltip("Generar contraseña segura y fácil de leer"));
        btnGenPass.setPrefWidth(36);
        btnGenPass.setOnAction(ev -> {
            String nueva = VpsDocumentationHelper.generarPasswordAdminAmigable(inst.getSlug());
            pwdAdmin.setText(nueva);
            txtAdminVisible.setText(nueva);
            btnOjoAdmin.setSelected(true);
            lblEstadoAdmin.setText("Nueva clave generada. Pulsa 'Aplicar en VPS' para activarla en el servidor.");
            lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #fbbf24;");
        });

        HBox boxPassAdmin = crearCampoPasswordConOjo(pwdAdmin, txtAdminVisible, btnOjoAdmin);
        boxPassAdmin.getChildren().add(btnGenPass);

        btnAplicarAdminVps = new Button("⚡ Generar / Aplicar Clave Admin en el VPS");
        btnAplicarAdminVps.setTooltip(new Tooltip("Actualiza (o crea) este correo y contraseña directamente en la BD del VPS para que el cliente ingrese sin errores"));
        btnAplicarAdminVps.setDisable(vps == null);
        btnAplicarAdminVps.setOnAction(ev -> aplicarAdminEnVps());

        boolean faltanCredsAdmin = emailSaved.isBlank() || passAdminSaved.isBlank();
        lblEstadoAdmin = new Label(faltanCredsAdmin
                ? "Nota: las contraseñas en BD están encriptadas (no se pueden leer). Escribe la actual o pulsa '⚡ Generar / Aplicar Clave Admin en el VPS' para crear una lista para tu cliente."
                : "Credenciales de administrador cargadas del almacén cifrado local.");
        lblEstadoAdmin.setWrapText(true);
        lblEstadoAdmin.setStyle(faltanCredsAdmin
                ? "-fx-font-size: 11px; -fx-text-fill: #fbbf24;"
                : "-fx-font-size: 11px; -fx-text-fill: #34d399;");

        chkIncluirAdminPass = new CheckBox("Incluir contraseña de administrador en el PDF");
        chkIncluirAdminPass.setSelected(true);
        chkRecordarAdmin = new CheckBox("Recordar usuario y contraseña admin (cifrados en VPSPilot)");
        chkRecordarAdmin.setSelected(true);

        VBox accionesAdmin = new VBox(6, btnAplicarAdminVps, lblEstadoAdmin, chkIncluirAdminPass, chkRecordarAdmin);

        GridPane gridAdmin = crearGrid();
        gridAdmin.add(new Label("Usuario / Correo Admin:"), 0, 0);
        gridAdmin.add(txtAdminEmail, 1, 0);
        gridAdmin.add(new Label("Contraseña Admin:"), 0, 1);
        gridAdmin.add(boxPassAdmin, 1, 1);
        gridAdmin.add(accionesAdmin, 1, 2);
        VBox secAdmin = crearCardSeccion("2. Credenciales del Administrador Web (acceso del cliente)", gridAdmin);

        // ==========================================================
        // 3. Base de Datos y Túnel SSH (Navicat)
        // ==========================================================
        String passBdSaved = (dbPassInicial != null && !dbPassInicial.isBlank())
                ? dbPassInicial
                : leerCredencial(inst.credencialDbId());

        cbMotorBd = new ComboBox<>(FXCollections.observableArrayList("MYSQL", "POSTGRESQL", "SQLITE", "NINGUNA"));
        cbMotorBd.setValue(inst.getTipoBaseDatos());
        cbMotorBd.setPrefWidth(150);
        txtPuertoBd = new TextField(String.valueOf(inst.getPuertoBaseDatos()));
        txtPuertoBd.setPrefWidth(80);
        cbMotorBd.setOnAction(ev -> {
            String m = cbMotorBd.getValue();
            if ("POSTGRESQL".equals(m) && "3306".equals(txtPuertoBd.getText().trim())) {
                txtPuertoBd.setText("5432");
            } else if ("MYSQL".equals(m) && "5432".equals(txtPuertoBd.getText().trim())) {
                txtPuertoBd.setText("3306");
            }
        });

        HBox filaMotorPuerto = new HBox(10, cbMotorBd, new Label("Puerto:"), txtPuertoBd);
        filaMotorPuerto.setAlignment(Pos.CENTER_LEFT);

        txtNombreBd = new TextField(inst.getNombreBaseDatos() != null ? inst.getNombreBaseDatos() : "");
        txtNombreBd.setPromptText("Nombre de la base de datos");
        txtUsuarioBd = new TextField(inst.getUsuarioBaseDatos() != null ? inst.getUsuarioBaseDatos() : "");
        txtUsuarioBd.setPromptText("Usuario de BD");

        pwdBd = new PasswordField();
        pwdBd.setText(passBdSaved);
        pwdBd.setPromptText("Contraseña BD (usa 'Detectar Datos del VPS' si está vacía)");
        txtBdVisible = new TextField(passBdSaved);
        txtBdVisible.setPromptText("Contraseña BD (usa 'Detectar Datos del VPS' si está vacía)");
        HBox boxPassBd = crearCampoPasswordConOjo(pwdBd, txtBdVisible, new ToggleButton("👁"));

        lblEstadoEnv = new Label(vps == null
                ? "Sin VPS asociado"
                : "Túnel SSH Navicat: " + vps.getUsuario() + "@" + vps.getHost() + ":" + vps.getPuerto()
                + " (" + (vps.getTipoAuth() == AuthType.KEY ? "Clave SSH" : "Contraseña") + ")");
        lblEstadoEnv.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        lblEstadoEnv.setWrapText(true);

        chkIncluirBdPass = new CheckBox("Incluir contraseña de Base de Datos en la guía Navicat");
        chkIncluirBdPass.setSelected(true);
        chkIncluirSshSecret = new CheckBox("Incluir contraseña / dato SSH del VPS para la pestaña Túnel SSH de Navicat");
        chkIncluirSshSecret.setSelected(true);

        GridPane gridDb = crearGrid();
        gridDb.add(new Label("Motor y Puerto:"), 0, 0);
        gridDb.add(filaMotorPuerto, 1, 0);
        gridDb.add(new Label("Base de Datos:"), 0, 1);
        gridDb.add(txtNombreBd, 1, 1);
        gridDb.add(new Label("Usuario BD:"), 0, 2);
        gridDb.add(txtUsuarioBd, 1, 2);
        gridDb.add(new Label("Contraseña BD:"), 0, 3);
        gridDb.add(boxPassBd, 1, 3);
        gridDb.add(lblEstadoEnv, 1, 4);
        gridDb.add(new VBox(6, chkIncluirBdPass, chkIncluirSshSecret), 1, 5);
        VBox secDb = crearCardSeccion("3. Base de Datos y Configuración Navicat (General + Túnel SSH)", gridDb);

        // ==========================================================
        // 4. Notas opcionales
        // ==========================================================
        txtNotas = new TextArea();
        txtNotas.setPromptText("Opcional: contacto de soporte, indicaciones de uso o notas de entrega…");
        txtNotas.setPrefRowCount(2);
        txtNotas.setWrapText(true);
        VBox secNotas = crearCardSeccion("4. Notas Adicionales (Opcional)", txtNotas);

        // Scroll para todo el formulario
        VBox cuerpoForm = new VBox(12, secLinks, secAdmin, secDb, secNotas);
        cuerpoForm.setPadding(new Insets(2, 6, 4, 2));
        ScrollPane scroll = new ScrollPane(cuerpoForm);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Barra inferior
        chkAbrirAlGuardar = new CheckBox("Abrir el PDF automáticamente al guardar");
        chkAbrirAlGuardar.setSelected(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnCancelar = new Button("Cancelar");
        btnCancelar.setOnAction(ev -> close());

        Button btnGenerar = new Button("📄 Guardar PDF…");
        btnGenerar.getStyleClass().add("boton-primario");
        btnGenerar.setStyle("-fx-font-weight: bold; -fx-padding: 8px 18px;");
        btnGenerar.setOnAction(ev -> guardarPdf());

        HBox barraInferior = new HBox(10, chkAbrirAlGuardar, spacer, btnCancelar, btnGenerar);
        barraInferior.setAlignment(Pos.CENTER_LEFT);
        barraInferior.setPadding(new Insets(6, 0, 0, 0));

        root.getChildren().addAll(header, scroll, barraInferior);
        pane.setContent(root);
    }

    /** Conecta al VPS por SSH y lee en 1 clic HTTPS, .env (BD) y correo real del admin en BD. */
    private void detectarTodoDesdeVps() {
        if (vps == null) return;
        btnDetectarVps.setDisable(true);
        btnAplicarAdminVps.setDisable(true);
        lblEstadoVps.setText("Conectando por SSH al VPS para detectar HTTPS, .env y correo admin…");
        lblEstadoVps.setStyle("-fx-font-size: 11px; -fx-text-fill: #60a5fa;");

        new Thread(() -> {
            try (SshConnection ssh = servicios.app().conectar(vps, servicios.prompt())) {
                DeploymentContext ctx = servicios.app().contexto(vps, inst, ssh, null);
                DatosVpsDetectados det = VpsDocumentationHelper.leerDatosRealesDesdeVps(ctx);

                Platform.runLater(() -> {
                    aplicarDatosDetectados(det);
                    btnDetectarVps.setDisable(false);
                    btnAplicarAdminVps.setDisable(false);
                });
            } catch (Exception e) {
                log.warn("Error leyendo datos reales del VPS para PDF: {}", e.getMessage());
                Platform.runLater(() -> {
                    btnDetectarVps.setDisable(false);
                    btnAplicarAdminVps.setDisable(false);
                    lblEstadoVps.setText("No se pudo conectar al VPS: " + e.getMessage());
                    lblEstadoVps.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");
                });
            }
        }, "pdf-detect-vps").start();
    }

    private void aplicarDatosDetectados(DatosVpsDetectados det) {
        Map<String, String> kv = det.env();

        // 1. Actualizar URLs si HTTPS está activo en VPS o APP_URL viene en .env
        String appUrl = kv.getOrDefault("APP_URL", "").trim().replaceAll("/+$", "");
        if (det.httpsActivo() && !appUrl.toLowerCase().startsWith("https://")) {
            String dom = inst.dominioPrincipal();
            if (dom != null && !dom.isBlank()) {
                appUrl = "https://" + dom;
            } else if (appUrl.toLowerCase().startsWith("http://")) {
                appUrl = "https://" + appUrl.substring(7);
            }
        }
        if (!appUrl.isBlank()) {
            txtUrlWeb.setText(appUrl);
            txtUrlLogin.setText(appUrl + "/login");
            txtUrlAdmin.setText(appUrl + "/admin/dashboard");
        }
        if (det.httpsActivo() && !inst.isHttpsActivado()) {
            inst.setHttpsActivado(true);
            try {
                servicios.app().instituciones.actualizar(inst);
            } catch (Exception ignored) {
            }
        }

        // 2. Rellenar datos de BD desde .env
        String conn = kv.getOrDefault("DB_CONNECTION", "").toLowerCase();
        if (conn.contains("pgsql") || conn.contains("postgres")) {
            cbMotorBd.setValue("POSTGRESQL");
        } else if (conn.contains("sqlite")) {
            cbMotorBd.setValue("SQLITE");
        } else if (conn.contains("mysql") || conn.contains("mariadb")) {
            cbMotorBd.setValue("MYSQL");
        }
        if (kv.containsKey("DB_PORT") && !kv.get("DB_PORT").isBlank()) {
            txtPuertoBd.setText(kv.get("DB_PORT"));
        }
        if (kv.containsKey("DB_DATABASE") && !kv.get("DB_DATABASE").isBlank()) {
            txtNombreBd.setText(kv.get("DB_DATABASE"));
        }
        if (kv.containsKey("DB_USERNAME") && !kv.get("DB_USERNAME").isBlank()) {
            txtUsuarioBd.setText(kv.get("DB_USERNAME"));
        }
        if (kv.containsKey("DB_PASSWORD") && !kv.get("DB_PASSWORD").isBlank()) {
            pwdBd.setText(kv.get("DB_PASSWORD"));
            txtBdVisible.setText(kv.get("DB_PASSWORD"));
        }

        // 3. Rellenar correo real del administrador desde la BD
        if (det.adminEmail() != null && !det.adminEmail().isBlank()) {
            txtAdminEmail.setText(det.adminEmail());
            guardarCredencialSiPresente(inst.credencialAdminEmailId(), det.adminEmail());
            if (pwdAdmin.getText().isBlank()) {
                lblEstadoAdmin.setText("Correo admin detectado del VPS (" + det.adminEmail()
                        + "). Ahora escribe su clave actual o pulsa '⚡ Generar / Aplicar Clave Admin en el VPS' para asignarle una nueva clave funcional.");
                lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #fbbf24;");
            } else {
                lblEstadoAdmin.setText("Correo admin confirmado en VPS: " + det.adminEmail());
                lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399;");
            }
        } else {
            lblEstadoAdmin.setText("No se encontró usuario admin en la BD. Escribe un correo y pulsa '⚡ Generar / Aplicar Clave Admin en el VPS' para crearlo.");
            lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #fbbf24;");
        }

        lblEstadoVps.setText("Sincronizado desde el VPS: " + (det.detalle().isBlank() ? "completado" : det.detalle()));
        lblEstadoVps.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399;");
    }

    /** Genera (si falta) y aplica la contraseña del administrador directamente en la BD del VPS. */
    private void aplicarAdminEnVps() {
        if (vps == null) return;

        String email = txtAdminEmail.getText().trim();
        if (email.isBlank()) {
            String dom = inst.dominioPrincipal();
            String sugerido = (dom != null && !dom.isBlank()) ? "admin@" + dom : "admin@instituto.edu.pe";
            txtAdminEmail.setText(sugerido);
            email = sugerido;
        }
        if (!email.contains("@")) {
            UiHelper.alertaAdvertencia("Correo inválido", null,
                    "Ingresa un correo electrónico válido para el administrador (ej. admin@dominio.edu.pe).");
            return;
        }

        String pass = pwdAdmin.getText();
        if (pass == null || pass.isBlank()) {
            pass = VpsDocumentationHelper.generarPasswordAdminAmigable(inst.getSlug());
            pwdAdmin.setText(pass);
            txtAdminVisible.setText(pass);
            btnOjoAdmin.setSelected(true);
        }
        if (pass.length() < 6) {
            UiHelper.alertaAdvertencia("Contraseña demasiado corta", null,
                    "La contraseña del administrador debe tener al menos 6 caracteres.");
            return;
        }

        boolean ok = UiHelper.confirmar(
                "Aplicar Credenciales Admin en el VPS",
                "¿Actualizar la contraseña de administrador en el servidor?",
                "Se configurará en la base de datos del VPS:\n\n"
                        + "  • Correo Admin:  " + email + "\n"
                        + "  • Nueva Clave:   " + pass + "\n\n"
                        + "Si ya existe un administrador (rol_id = 1), se actualizará su contraseña y se activará la cuenta. "
                        + "Si no existe, se creará automáticamente. Así tu cliente podrá ingresar de inmediato con los datos del PDF.");
        if (!ok) {
            return;
        }

        final String finalEmail = email;
        final String finalPass = pass;
        btnAplicarAdminVps.setDisable(true);
        btnDetectarVps.setDisable(true);
        lblEstadoAdmin.setText("Aplicando contraseña del administrador en la base de datos del VPS…");
        lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #60a5fa;");

        new Thread(() -> {
            try (SshConnection ssh = servicios.app().conectar(vps, servicios.prompt())) {
                DeploymentContext ctx = servicios.app().contexto(vps, inst, ssh, null);
                ResultadoAdminVps res = VpsDocumentationHelper.aplicarCredencialesAdminEnVps(
                        ctx, finalEmail, finalPass, "Administrador " + inst.getNombre());

                Platform.runLater(() -> {
                    btnAplicarAdminVps.setDisable(false);
                    btnDetectarVps.setDisable(false);
                    if (res.exito()) {
                        guardarCredencialSiPresente(inst.credencialAdminEmailId(), res.emailAplicado());
                        guardarCredencialSiPresente(inst.credencialAdminPassId(), finalPass);
                        lblEstadoAdmin.setText("✔ " + res.mensaje() + " — ¡Lista para entregar en el PDF!");
                        lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #34d399;");
                        UiHelper.alertaExito("Credenciales Aplicadas en el VPS",
                                res.mensaje() + "\n\nYa puedes guardar el PDF y enviárselo a tu cliente.");
                    } else {
                        lblEstadoAdmin.setText("Error: " + res.mensaje());
                        lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");
                        UiHelper.alertaError("No se pudo aplicar en el VPS", res.mensaje());
                    }
                });
            } catch (Exception e) {
                log.error("Error aplicando credenciales admin en VPS: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    btnAplicarAdminVps.setDisable(false);
                    btnDetectarVps.setDisable(false);
                    lblEstadoAdmin.setText("Error de conexión SSH: " + e.getMessage());
                    lblEstadoAdmin.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");
                    UiHelper.alertaError("Error SSH", e.getMessage());
                });
            }
        }, "pdf-apply-admin").start();
    }

    private void guardarPdf() {
        String adminEmail = txtAdminEmail.getText().trim();
        String adminPass = pwdAdmin.getText();
        String dbPass = pwdBd.getText();

        // Protección: evitar entregar accidentalmente un PDF al cliente sin credenciales de administrador
        boolean faltaAdminEmail = adminEmail.isBlank();
        boolean faltaAdminPass = chkIncluirAdminPass.isSelected() && (adminPass == null || adminPass.isBlank());
        if (faltaAdminEmail || faltaAdminPass) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Credenciales de Administrador Incompletas");
            alert.setHeaderText("Tu cliente necesita usuario y contraseña para ingresar al sistema");
            alert.setContentText("Todavía falta completar: "
                    + (faltaAdminEmail && faltaAdminPass ? "Correo y Contraseña de Administrador"
                    : (faltaAdminEmail ? "Correo de Administrador" : "Contraseña de Administrador"))
                    + ".\n\nRecomendación: vuelve al formulario y pulsa:\n"
                    + "  1) '🔄 Detectar Datos del VPS' (para cargar el correo admin real)\n"
                    + "  2) '⚡ Generar / Aplicar Clave Admin en el VPS' (para crear y activar una clave lista para tu cliente)\n\n"
                    + "¿Qué deseas hacer?");
            alert.getDialogPane().getStyleClass().add("dialogo-moderno");
            ButtonType btnVolver = new ButtonType("← Volver y completar", ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType btnSeguir = new ButtonType("Generar PDF sin credenciales", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnVolver, btnSeguir);
            var choice = alert.showAndWait();
            if (choice.isEmpty() || choice.get() != btnSeguir) {
                if (faltaAdminEmail) {
                    txtAdminEmail.requestFocus();
                } else {
                    pwdAdmin.requestFocus();
                }
                return;
            }
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar Documentación Técnica en PDF");
        String slugLimpio = (inst.getSlug() == null || inst.getSlug().isBlank()) ? "proyecto" : inst.getSlug();
        String hoy = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        fc.setInitialFileName("Documentacion-" + slugLimpio + "-" + hoy + ".pdf");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Documento PDF (*.pdf)", "*.pdf"));

        Path carpetaInicial = Path.of(System.getProperty("user.home"), "Documents");
        if (!Files.isDirectory(carpetaInicial)) {
            carpetaInicial = Path.of(System.getProperty("user.home"), "Desktop");
        }
        if (Files.isDirectory(carpetaInicial)) {
            fc.setInitialDirectory(carpetaInicial.toFile());
        }

        File destino = fc.showSaveDialog(getDialogPane().getScene().getWindow());
        if (destino == null) {
            return;
        }
        if (!destino.getName().toLowerCase().endsWith(".pdf")) {
            destino = new File(destino.getAbsolutePath() + ".pdf");
        }

        // Guardar credenciales cifradas si el usuario marcó recordar
        if (chkRecordarAdmin.isSelected()) {
            guardarCredencialSiPresente(inst.credencialAdminEmailId(), adminEmail);
            guardarCredencialSiPresente(inst.credencialAdminPassId(), adminPass);
        }
        if (dbPass != null && !dbPass.isBlank()) {
            guardarCredencialSiPresente(inst.credencialDbId(), dbPass);
        }

        int puertoBd = 3306;
        try {
            puertoBd = Integer.parseInt(txtPuertoBd.getText().trim());
        } catch (NumberFormatException ignored) {
            puertoBd = "POSTGRESQL".equalsIgnoreCase(cbMotorBd.getValue()) ? 5432 : 3306;
        }

        String urlWeb = txtUrlWeb.getText().trim();
        boolean https = urlWeb.toLowerCase().startsWith("https://") || inst.isHttpsActivado();
        String rutaBase = resolverRutaBase();
        String commit = inst.getCommitDesplegado() == null ? "" : inst.getCommitDesplegado().trim();
        String commitCorto = commit.length() > 8 ? commit.substring(0, 8) : commit;

        DatosDocumentacion datos = new DatosDocumentacion(
                inst.getNombre(),
                inst.getSlug(),
                inst.dominioPrincipal(),
                urlWeb,
                txtUrlLogin.getText().trim(),
                txtUrlAdmin.getText().trim(),
                https,
                inst.repositorioEfectivo(servicios.app().ajustes),
                inst.getRama(),
                commitCorto,
                adminEmail,
                adminPass,
                chkIncluirAdminPass.isSelected(),
                cbMotorBd.getValue(),
                "127.0.0.1",
                puertoBd,
                txtNombreBd.getText().trim(),
                txtUsuarioBd.getText().trim(),
                dbPass,
                chkIncluirBdPass.isSelected(),
                vps != null ? vps.getNombre() : "Servidor VPS",
                vps != null ? vps.getHost() : "-",
                vps != null && vps.getPuerto() > 0 ? vps.getPuerto() : 22,
                vps != null ? vps.getUsuario() : "root",
                vps != null ? vps.getTipoAuth() : AuthType.PASSWORD,
                sshSecretoGuardado,
                chkIncluirSshSecret.isSelected(),
                rutaBase,
                txtNotas.getText(),
                null
        );

        try {
            DocumentacionPdfGenerator.generar(datos, destino.toPath());
            if (chkAbrirAlGuardar.isSelected()) {
                abrirArchivo(destino);
            }
            UiHelper.alertaExito("PDF Generado Correctamente",
                    "La documentación completa se guardó en:\n" + destino.getAbsolutePath());
            close();
        } catch (Exception e) {
            log.error("Error generando PDF de documentación: {}", e.getMessage(), e);
            UiHelper.alertaError("No se pudo generar el PDF", e.getMessage());
        }
    }

    private String resolverRutaBase() {
        String custom = inst.getRutaInstalacion();
        if (custom != null && !custom.isBlank()) {
            String clean = custom.trim().replaceAll("/+$", "");
            if (clean.startsWith("/") && !clean.contains("..") && !clean.contains(" ")) {
                String def = servicios.app().ajustes.getRutaInstalacionBase().replaceAll("/+$", "");
                if ("/var/www".equals(clean) || def.equals(clean)) {
                    return clean + "/" + inst.getSlug();
                }
                return clean;
            }
        }
        return servicios.app().ajustes.getRutaInstalacionBase().replaceAll("/+$", "") + "/" + inst.getSlug();
    }

    private String leerCredencial(String id) {
        try {
            return servicios.app().credenciales.obtener(id).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private void guardarCredencialSiPresente(String id, String valor) {
        if (valor == null || valor.isBlank()) return;
        try {
            servicios.app().credenciales.guardar(id, valor.trim());
        } catch (Exception e) {
            log.warn("No se pudo guardar credencial '{}': {}", id, e.getMessage());
        }
    }

    private static void abrirArchivo(File f) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ignored) {
        }
    }

    private static GridPane crearGrid() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(8);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(155);
        c0.setPrefWidth(165);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        return g;
    }

    private static VBox crearCardSeccion(String titulo, javafx.scene.Node contenido) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12));
        Label lbl = new Label(titulo);
        lbl.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #a5b4fc;");
        card.getChildren().addAll(lbl, contenido);
        return card;
    }

    private static HBox crearCampoPasswordConOjo(PasswordField pwd, TextField visible, ToggleButton btnOjo) {
        visible.setManaged(false);
        visible.setVisible(false);
        pwd.textProperty().bindBidirectional(visible.textProperty());

        StackPane stack = new StackPane(pwd, visible);
        HBox.setHgrow(stack, Priority.ALWAYS);

        btnOjo.setTooltip(new Tooltip("Mostrar / ocultar contraseña"));
        btnOjo.setPrefWidth(36);
        btnOjo.selectedProperty().addListener((obs, old, mostrar) -> {
            visible.setVisible(mostrar);
            visible.setManaged(mostrar);
            pwd.setVisible(!mostrar);
            pwd.setManaged(!mostrar);
        });

        HBox row = new HBox(6, stack, btnOjo);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
