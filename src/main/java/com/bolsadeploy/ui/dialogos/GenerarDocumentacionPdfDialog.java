package com.bolsadeploy.ui.dialogos;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.reports.DocumentacionPdfGenerator;
import com.bolsadeploy.reports.DocumentacionPdfGenerator.DatosDocumentacion;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Diálogo amigable y prellenado para generar el PDF de documentación técnica,
 * enlaces, credenciales de administrador y configuración paso a paso de Navicat.
 */
public class GenerarDocumentacionPdfDialog extends Dialog<Void> {

    private static final Logger log = LoggerFactory.getLogger(GenerarDocumentacionPdfDialog.class);

    private final Servicios servicios;
    private final Institution inst;
    private final VpsServer vps;

    // Enlaces
    private TextField txtUrlWeb;
    private TextField txtUrlLogin;
    private TextField txtUrlAdmin;

    // Credenciales Admin
    private TextField txtAdminEmail;
    private PasswordField pwdAdmin;
    private TextField txtAdminVisible;
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
    private Button btnLeerEnv;

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
        pane.setPrefSize(700, 650);

        cargarSecretosLocales(adminEmailInicial, adminPassInicial, dbPassInicial);
        construirContenido(pane, adminEmailInicial, adminPassInicial, dbPassInicial);

        pane.getButtonTypes().add(ButtonType.CLOSE);
        var closeBtn = pane.lookupButton(ButtonType.CLOSE);
        if (closeBtn != null) {
            closeBtn.setVisible(false);
            closeBtn.setManaged(false);
        }
    }

    private void cargarSecretosLocales(String adminEmailInicial, String adminPassInicial, String dbPassInicial) {
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

        // Encabezado amigable
        VBox header = new VBox(4);
        header.getStyleClass().add("card");
        header.setPadding(new Insets(12));
        header.setStyle("-fx-border-color: #4f46e5; -fx-border-width: 0 0 0 4px;");
        Label lblTit = new Label("Documentación PDF de Entrega — " + inst.getNombre());
        lblTit.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label lblSub = new Label("Revisa o completa los datos prellenados. El PDF incluirá enlaces clicables, "
                + "credenciales del administrador, guía paso a paso de Navicat (General + Túnel SSH) y rutas del VPS.");
        lblSub.setWrapText(true);
        lblSub.getStyleClass().add("seccion-descripcion");
        header.getChildren().addAll(lblTit, lblSub);

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
        // 2. Credenciales Admin Web
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
        txtAdminEmail.setPromptText("ej. admin@instituto.edu.pe");

        pwdAdmin = new PasswordField();
        pwdAdmin.setText(passAdminSaved);
        pwdAdmin.setPromptText("Contraseña de ingreso al panel admin");
        txtAdminVisible = new TextField(passAdminSaved);
        txtAdminVisible.setPromptText("Contraseña de ingreso al panel admin");
        HBox boxPassAdmin = crearCampoPasswordConOjo(pwdAdmin, txtAdminVisible);

        chkIncluirAdminPass = new CheckBox("Incluir contraseña de administrador en el PDF");
        chkIncluirAdminPass.setSelected(true);
        chkRecordarAdmin = new CheckBox("Recordar usuario y contraseña admin (cifrados en VPSPilot)");
        chkRecordarAdmin.setSelected(true);

        GridPane gridAdmin = crearGrid();
        gridAdmin.add(new Label("Usuario / Correo Admin:"), 0, 0);
        gridAdmin.add(txtAdminEmail, 1, 0);
        gridAdmin.add(new Label("Contraseña Admin:"), 0, 1);
        gridAdmin.add(boxPassAdmin, 1, 1);
        gridAdmin.add(new VBox(6, chkIncluirAdminPass, chkRecordarAdmin), 1, 2);
        VBox secAdmin = crearCardSeccion("2. Credenciales del Administrador Web", gridAdmin);

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
        pwdBd.setPromptText("Contraseña BD (o pulsa 'Leer .env del VPS')");
        txtBdVisible = new TextField(passBdSaved);
        txtBdVisible.setPromptText("Contraseña BD (o pulsa 'Leer .env del VPS')");
        HBox boxPassBd = crearCampoPasswordConOjo(pwdBd, txtBdVisible);

        btnLeerEnv = new Button("🔄 Leer .env del VPS");
        btnLeerEnv.setTooltip(new Tooltip("Conecta al VPS por SSH y rellena automáticamente DB_DATABASE, DB_USERNAME y DB_PASSWORD desde shared/.env"));
        btnLeerEnv.setDisable(vps == null);
        btnLeerEnv.setOnAction(ev -> leerEnvDesdeVps());

        lblEstadoEnv = new Label(vps == null
                ? "Sin VPS asociado"
                : "Túnel SSH Navicat: " + vps.getUsuario() + "@" + vps.getHost() + ":" + vps.getPuerto()
                + " (" + (vps.getTipoAuth() == AuthType.KEY ? "Clave SSH" : "Contraseña") + ")");
        lblEstadoEnv.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        lblEstadoEnv.setWrapText(true);

        HBox filaLeerEnv = new HBox(10, btnLeerEnv, lblEstadoEnv);
        filaLeerEnv.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lblEstadoEnv, Priority.ALWAYS);

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
        gridDb.add(filaLeerEnv, 1, 4);
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

    /** Conecta al VPS por SSH en segundo plano y extrae DB_* / APP_URL de shared/.env. */
    private void leerEnvDesdeVps() {
        if (vps == null) return;
        btnLeerEnv.setDisable(true);
        lblEstadoEnv.setText("Conectando por SSH para leer shared/.env…");
        lblEstadoEnv.setStyle("-fx-font-size: 11px; -fx-text-fill: #60a5fa;");

        new Thread(() -> {
            try (SshConnection ssh = servicios.app().conectar(vps, servicios.prompt())) {
                DeploymentContext ctx = servicios.app().contexto(vps, inst, ssh, null);
                String envFile = ctx.rutaShared() + "/.env";
                CommandResult res = ctx.ejecutar(
                        "FILE=\"" + envFile + "\"; [ -s \"$FILE\" ] || FILE=\"" + ctx.rutaReleaseActual() + "/.env\"; "
                                + "if [ -s \"$FILE\" ]; then grep -E '^(DB_CONNECTION|DB_HOST|DB_PORT|DB_DATABASE|DB_USERNAME|DB_PASSWORD|APP_URL)=' \"$FILE\" 2>/dev/null; "
                                + "else echo '__NO_ENV__'; fi", 25);
                String out = res.stdout() == null ? "" : res.stdout().trim();
                if (out.isBlank() || out.contains("__NO_ENV__")) {
                    Platform.runLater(() -> {
                        btnLeerEnv.setDisable(false);
                        lblEstadoEnv.setText("No se encontró shared/.env en " + ctx.rutaShared());
                        lblEstadoEnv.setStyle("-fx-font-size: 11px; -fx-text-fill: #f59e0b;");
                    });
                    return;
                }
                Map<String, String> kv = parsearEnv(out);
                Platform.runLater(() -> {
                    aplicarEnvEnCampos(kv);
                    btnLeerEnv.setDisable(false);
                    lblEstadoEnv.setText("Datos cargados desde shared/.env del VPS.");
                    lblEstadoEnv.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399;");
                });
            } catch (Exception e) {
                log.warn("Error leyendo .env remoto para PDF: {}", e.getMessage());
                Platform.runLater(() -> {
                    btnLeerEnv.setDisable(false);
                    lblEstadoEnv.setText("No se pudo leer .env: " + e.getMessage());
                    lblEstadoEnv.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");
                });
            }
        }, "pdf-read-env").start();
    }

    private static Map<String, String> parsearEnv(String texto) {
        Map<String, String> mapa = new HashMap<>();
        for (String linea : texto.split("\\R+")) {
            int eq = linea.indexOf('=');
            if (eq <= 0) continue;
            String k = linea.substring(0, eq).trim();
            String v = linea.substring(eq + 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            mapa.put(k, v);
        }
        return mapa;
    }

    private void aplicarEnvEnCampos(Map<String, String> kv) {
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
        if (kv.containsKey("APP_URL") && !kv.get("APP_URL").isBlank()) {
            String appUrl = kv.get("APP_URL").replaceAll("/+$", "");
            txtUrlWeb.setText(appUrl);
            txtUrlLogin.setText(appUrl + "/login");
            txtUrlAdmin.setText(appUrl + "/admin/dashboard");
        }
    }

    private void guardarPdf() {
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

        String adminEmail = txtAdminEmail.getText().trim();
        String adminPass = pwdAdmin.getText();
        String dbPass = pwdBd.getText();

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

    private static HBox crearCampoPasswordConOjo(PasswordField pwd, TextField visible) {
        visible.setManaged(false);
        visible.setVisible(false);
        pwd.textProperty().bindBidirectional(visible.textProperty());

        StackPane stack = new StackPane(pwd, visible);
        HBox.setHgrow(stack, Priority.ALWAYS);

        ToggleButton btnOjo = new ToggleButton("👁");
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
