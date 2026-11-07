package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.application.AppContext;
import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuración global de la aplicación: temas visuales, rutas de respaldos,
 * conexión con GitHub, parámetros SSH y almacén de claves seguras.
 */
public class ConfiguracionVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    private RadioButton rbOscuro;
    private RadioButton rbClaro;
    private TextField txtCarpetaRespaldos;
    private TextField txtRepoGithub;
    private TextField txtRamaDefault;
    private PasswordField txtGithubToken;
    private TextField txtPuertoSsh;
    private TextField txtTimeoutSsh;
    private Spinner<Integer> spConcurrencia;
    private CheckBox chkRespaldoPreActualizacion;
    private CheckBox chkVerificarHostKeys;
    private CheckBox chkLoginWindows;

    public ConfiguracionVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(20);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1000);

        scroll = new ScrollPane(contenedor);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Configuración General",
                "Ajustes globales del orquestador, parámetros de conexión, tokens y almacenamiento.");

        // Apariencia
        VBox secApariencia = new VBox(10);
        secApariencia.getStyleClass().add("card");
        secApariencia.setPadding(new Insets(16));

        Label lblApariencia = new Label("Tema Visual de la Aplicación");
        lblApariencia.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        ToggleGroup tgTema = new ToggleGroup();
        rbOscuro = new RadioButton("Tema Oscuro Profesional (Recomendado)");
        rbOscuro.setToggleGroup(tgTema);

        rbClaro = new RadioButton("Tema Claro");
        rbClaro.setToggleGroup(tgTema);

        tgTema.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == rbClaro) {
                servicios.mainApp().cambiarTema(AppSettings.TEMA_CLARO);
            } else {
                servicios.mainApp().cambiarTema(AppSettings.TEMA_OSCURO);
            }
        });

        HBox boxTemas = new HBox(20, rbOscuro, rbClaro);
        secApariencia.getChildren().addAll(lblApariencia, boxTemas);

        // Rutas y Repositorio
        VBox secRutas = new VBox(12);
        secRutas.getStyleClass().add("card");
        secRutas.setPadding(new Insets(16));

        Label lblRutasTit = new Label("Repositorio y Rutas Locales");
        lblRutasTit.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        txtRepoGithub = new TextField();
        txtRamaDefault = new TextField();

        txtCarpetaRespaldos = new TextField();
        txtCarpetaRespaldos.setPrefWidth(450);

        Button btnExaminar = new Button("Examinar...");
        btnExaminar.setOnAction(ev -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Seleccionar carpeta local para respaldos");
            File sel = dc.showDialog(contenedor.getScene().getWindow());
            if (sel != null) {
                txtCarpetaRespaldos.setText(sel.getAbsolutePath());
            }
        });
        HBox boxRespaldos = new HBox(8, txtCarpetaRespaldos, btnExaminar);

        txtGithubToken = new PasswordField();
        txtGithubToken.setPromptText("(Dejar vacío para no cambiar o si el repositorio es público)");

        secRutas.getChildren().addAll(lblRutasTit,
                UiHelper.campoFormulario("Repositorio Oficial de GitHub:", txtRepoGithub, "URL https del repositorio con el código fuente."),
                UiHelper.campoFormulario("Rama Predeterminada:", txtRamaDefault, "Rama de trabajo para instalaciones y actualizaciones."),
                UiHelper.campoFormulario("Carpeta Local de Descarga de Respaldos:", boxRespaldos, "Ubicación en tu computadora Windows donde se guardan las descargas SFTP."),
                UiHelper.campoFormulario("GitHub Personal Access Token (Opcional):", txtGithubToken, "Requerido si el repositorio es privado o para evitar límites de API."));

        // Parámetros SSH y Concurrencia
        VBox secSsh = new VBox(12);
        secSsh.getStyleClass().add("card");
        secSsh.setPadding(new Insets(16));

        Label lblSshTit = new Label("Parámetros SSH y Actualizaciones");
        lblSshTit.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        txtPuertoSsh = new TextField();
        txtTimeoutSsh = new TextField();

        spConcurrencia = new Spinner<>(1, 5, 2);

        chkRespaldoPreActualizacion = new CheckBox("Crear respaldo automático de base de datos antes de cada actualización");
        chkVerificarHostKeys = new CheckBox("Verificación estricta de claves de host SSH (Recomendado)");

        GridPane gridSsh = new GridPane();
        gridSsh.setHgap(12);
        gridSsh.setVgap(10);

        gridSsh.add(new Label("Puerto SSH por defecto:"), 0, 0);
        gridSsh.add(txtPuertoSsh, 1, 0);

        gridSsh.add(new Label("Timeout SSH (segundos):"), 0, 1);
        gridSsh.add(txtTimeoutSsh, 1, 1);

        gridSsh.add(new Label("Concurrencia de actualizaciones:"), 0, 2);
        gridSsh.add(spConcurrencia, 1, 2);

        secSsh.getChildren().addAll(lblSshTit, gridSsh, chkRespaldoPreActualizacion, chkVerificarHostKeys);

        // Seguridad y Datos Locales
        VBox secSeguridad = new VBox(12);
        secSeguridad.getStyleClass().add("card");
        secSeguridad.setPadding(new Insets(16));

        Label lblSegTit = new Label("Seguridad del Entorno");
        lblSegTit.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label lblMecanismo = new Label("Mecanismo de cifrado de credenciales: " + servicios.app().credenciales.mecanismo());
        lblMecanismo.setStyle("-fx-font-weight: bold; -fx-text-fill: #10b981;");

        chkLoginWindows = new CheckBox("Requerir inicio de sesión con cuenta de Windows al abrir VPSPilot");
        chkLoginWindows.setStyle("-fx-font-weight: bold;");

        HBox boxBotonesDatos = new HBox(12);
        Button btnAbrirDatos = new Button("📂 Abrir Carpeta de Datos del Programa");
        btnAbrirDatos.setOnAction(ev -> abrirCarpeta(AppContext.carpetaDatos()));

        Button btnVerKnownHosts = new Button("🔑 Ver Claves SSH Conocidas");
        btnVerKnownHosts.setOnAction(ev -> mostrarKnownHosts());

        boxBotonesDatos.getChildren().addAll(btnAbrirDatos, btnVerKnownHosts);
        secSeguridad.getChildren().addAll(lblSegTit, lblMecanismo, chkLoginWindows, boxBotonesDatos);

        // Botón Guardar
        Button btnGuardar = new Button("💾 Guardar Ajustes");
        btnGuardar.getStyleClass().add("boton-primario");
        btnGuardar.setStyle("-fx-font-size: 14px; -fx-padding: 8px 24px;");
        btnGuardar.setOnAction(ev -> guardarAjustes());

        contenedor.getChildren().addAll(cabecera, secApariencia, secRutas, secSsh, secSeguridad, btnGuardar);
    }

    private void abrirCarpeta(Path ruta) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(ruta.toFile());
            }
        } catch (Exception e) {
            UiHelper.alertaError("Error", "No se pudo abrir la carpeta: " + e.getMessage());
        }
    }

    private void mostrarKnownHosts() {
        try {
            Path kh = AppContext.carpetaDatos().resolve("known_hosts");
            String contenido = Files.exists(kh) ? Files.readString(kh) : "(No hay claves registradas aún)";
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Known Hosts SSH");
            alert.setHeaderText("Claves de servidores de confianza registradas:");
            TextArea ta = new TextArea(contenido);
            ta.setEditable(false);
            ta.setPrefRowCount(10);
            alert.getDialogPane().setContent(ta);
            alert.showAndWait();
        } catch (Exception e) {
            UiHelper.alertaError("Error", e.getMessage());
        }
    }

    private void guardarAjustes() {
        try {
            AppSettings a = servicios.app().ajustes;
            a.setTema(rbClaro.isSelected() ? AppSettings.TEMA_CLARO : AppSettings.TEMA_OSCURO);
            a.setCarpetaRespaldos(txtCarpetaRespaldos.getText().trim());
            a.setRepoGitHub(txtRepoGithub.getText().trim());
            a.setRamaDefault(txtRamaDefault.getText().trim());

            if (!txtGithubToken.getText().isBlank()) {
                a.setGithubToken(txtGithubToken.getText().trim());
            }

            try {
                a.setPuertoSshDefault(Integer.parseInt(txtPuertoSsh.getText().trim()));
            } catch (Exception ignored) {
            }

            try {
                a.setTimeoutSshSeg(Integer.parseInt(txtTimeoutSsh.getText().trim()));
            } catch (Exception ignored) {
            }

            a.setConcurrenciaActualizaciones(spConcurrencia.getValue());
            a.setRespaldoPreActualizacion(chkRespaldoPreActualizacion.isSelected());
            a.setVerificarHostKeys(chkVerificarHostKeys.isSelected());
            a.setRequerirLoginWindows(chkLoginWindows.isSelected());

            servicios.app().ajustesRepo.guardar(a);
            servicios.app().recargarAjustes();

            UiHelper.alertaExito("Ajustes Guardados", "Las preferencias fueron actualizadas y guardadas en disco.");
        } catch (Exception e) {
            UiHelper.alertaError("Error al Guardar", e.getMessage());
        }
    }

    @Override
    public Node raiz() {
        return scroll;
    }

    @Override
    public void alMostrar() {
        AppSettings a = servicios.app().ajustes;
        if (AppSettings.TEMA_CLARO.equalsIgnoreCase(a.getTema())) {
            rbClaro.setSelected(true);
        } else {
            rbOscuro.setSelected(true);
        }

        txtCarpetaRespaldos.setText(a.getCarpetaRespaldos() != null ? a.getCarpetaRespaldos() : "");
        txtRepoGithub.setText(a.getRepoGitHub());
        txtRamaDefault.setText(a.getRamaDefault());
        txtPuertoSsh.setText(String.valueOf(a.getPuertoSshDefault()));
        txtTimeoutSsh.setText(String.valueOf(a.getTimeoutSshSeg()));
        spConcurrencia.getValueFactory().setValue(a.getConcurrenciaActualizaciones());
        chkRespaldoPreActualizacion.setSelected(a.isRespaldoPreActualizacion());
        chkVerificarHostKeys.setSelected(a.isVerificarHostKeys());
        chkLoginWindows.setSelected(a.isRequerirLoginWindows());
    }
}
