package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.application.DeploymentService;
import com.bolsadeploy.domain.*;
import com.bolsadeploy.security.CommandValidator;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.ConsolaOperaciones;
import com.bolsadeploy.ui.componentes.UiHelper;
import com.bolsadeploy.ui.dialogos.ReporteDespliegueDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Asistente (Wizard) de 5 pasos para realizar un nuevo despliegue automatizado.
 */
public class DespliegueWizardVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(DespliegueWizardVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    // Pasos del Wizard
    private int pasoActual = 1;
    private final StackPane panelPasos;
    private final HBox indicadorPasos;

    // Campos Paso 1
    private ComboBox<Institution> cbInstitucion;
    private Label lblVpsAsociado;
    private TextField txtRepoGit;
    private TextField txtRamaGit;

    // Campos Paso 2
    private TextField txtDominio;
    private CheckBox chkHttps;
    private CheckBox chkRedis;
    private TextField txtDirectorioBase;

    // Campos Paso 3
    private CheckBox chkConfigurarDb;
    private CheckBox chkImportarEsquemaBolsa;
    private TextField txtDbNombre;
    private TextField txtDbUsuario;
    private PasswordField txtDbPassword;
    private CheckBox chkAdoptarDb;
    private TextField txtAdminNombre;
    private TextField txtAdminEmail;
    private PasswordField txtAdminPassword;

    // Paso 4: Diagnóstico previo
    private TextArea txtResumenDiagPrevio;
    private Button btnEjecutarDiag;
    private boolean diagPrevioOk = false;

    // Paso 5: Consola de ejecución
    private ConsolaOperaciones consola;
    private Button btnIniciarDespliegue;

    // Controles de navegación del Wizard
    private Button btnAnterior;
    private Button btnSiguiente;

    public DespliegueWizardVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1100);

        scroll = new ScrollPane(contenedor);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");

        panelPasos = new StackPane();
        indicadorPasos = new HBox(10);
        indicadorPasos.setAlignment(Pos.CENTER);

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Asistente de Nuevo Despliegue",
                "Instala la bolsa laboral completa en un VPS Ubuntu siguiendo las 11 etapas de producción.");

        actualizarIndicadorPasos();

        // Construir contenido de cada paso
        VBox p1 = paso1Institucion();
        VBox p2 = paso2DominioYRed();
        VBox p3 = paso3BaseDatosYAdmin();
        VBox p4 = paso4DiagnosticoPrevio();
        VBox p5 = paso5Ejecucion();

        panelPasos.getChildren().addAll(p1, p2, p3, p4, p5);

        // Barra inferior de navegación
        HBox barraNav = new HBox(12);
        barraNav.setAlignment(Pos.CENTER_RIGHT);
        barraNav.setPadding(new Insets(16, 0, 0, 0));

        btnAnterior = new Button("⬅ Anterior");
        btnAnterior.setOnAction(ev -> retrocederPaso());

        btnSiguiente = new Button("Siguiente ➡");
        btnSiguiente.getStyleClass().add("boton-primario");
        btnSiguiente.setOnAction(ev -> avanzarPaso());

        barraNav.getChildren().addAll(btnAnterior, btnSiguiente);

        contenedor.getChildren().addAll(cabecera, indicadorPasos, panelPasos, barraNav);
        mostrarPaso(1);
    }

    private void actualizarIndicadorPasos() {
        indicadorPasos.getChildren().clear();
        String[] nombres = {"1. Institución", "2. Dominio y Red", "3. Base de Datos", "4. Diagnóstico Previo", "5. Ejecución"};
        for (int i = 0; i < nombres.length; i++) {
            Label lbl = new Label(nombres[i]);
            lbl.getStyleClass().add("wizard-step");
            if (i + 1 == pasoActual) {
                lbl.getStyleClass().add("wizard-step-activo");
            } else if (i + 1 < pasoActual) {
                lbl.getStyleClass().add("wizard-step-completado");
            }
            indicadorPasos.getChildren().add(lbl);
        }
    }

    private VBox paso1Institucion() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(20));

        Label lbl = new Label("Paso 1: Selección de Institución y Servidor VPS");
        lbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        cbInstitucion = new ComboBox<>();
        cbInstitucion.setPrefWidth(400);
        cbInstitucion.setPromptText("Selecciona una institución...");

        txtRepoGit = new TextField();
        txtRepoGit.setPromptText("ej. mi-usuario/mi-repo o https://github.com/...");

        txtRamaGit = new TextField("main");
        txtRamaGit.setPromptText("ej. main, master, prod...");

        cbInstitucion.setOnAction(ev -> {
            Institution inst = cbInstitucion.getValue();
            if (inst != null) {
                try {
                    VpsServer v = servicios.app().vpsDe(inst);
                    lblVpsAsociado.setText("Servidor VPS asociado: " + v.getNombre() + " (" + v.getHost() + ":" + v.getPuerto() + ")");
                    txtDominio.setText(inst.getDominio());
                    String slug = inst.getSlug() != null ? inst.getSlug() : "inst";
                    txtDbNombre.setText("db_" + slug.replaceAll("[^a-zA-Z0-9_]", ""));
                    txtDbUsuario.setText("usr_" + slug.replaceAll("[^a-zA-Z0-9_]", ""));
                    txtRepoGit.setText(inst.repositorioEfectivo(servicios.app().ajustes));
                    txtRamaGit.setText(inst.getRama() != null && !inst.getRama().isBlank() ? inst.getRama() : "main");
                } catch (Exception e) {
                    lblVpsAsociado.setText("Advertencia: " + e.getMessage());
                }
            }
        });

        lblVpsAsociado = new Label("Servidor VPS asociado: -");
        lblVpsAsociado.setStyle("-fx-font-style: italic;");

        box.getChildren().addAll(lbl,
                UiHelper.campoFormulario("Institución o Proyecto a Desplegar:", cbInstitucion,
                        "Selecciona la institución destino. Si no existe, créala en 'Instituciones'."),
                lblVpsAsociado,
                UiHelper.campoFormulario("Repositorio Git a Desplegar:", txtRepoGit,
                        "Puedes subir cualquier repositorio (ej. usuario/repo o URL completa https://github.com/...)"),
                UiHelper.campoFormulario("Rama o Tag Git:", txtRamaGit,
                        "Rama que se clonará y desplegará en el VPS (por defecto 'main')."));
        return box;
    }

    private VBox paso2DominioYRed() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(20));

        Label lbl = new Label("Paso 2: Dominio FQDN y Configuración Web");
        lbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        txtDominio = new TextField();
        txtDominio.setPromptText("ej. bolsa.ejemplo.edu.pe");

        chkHttps = new CheckBox("Activar HTTPS con Let's Encrypt (Certbot)");
        chkHttps.setSelected(true);

        chkRedis = new CheckBox("Activar servicio Redis para caché y sesiones (Opcional)");

        txtDirectorioBase = new TextField("/var/www");

        box.getChildren().addAll(lbl,
                UiHelper.campoFormulario("Dominio Público FQDN:", txtDominio, "El dominio debe apuntar por DNS a la IP del servidor VPS."),
                chkHttps,
                chkRedis,
                UiHelper.campoFormulario("Ruta Base en el VPS:", txtDirectorioBase, "Directorio donde se creará la carpeta del proyecto."));
        return box;
    }

    private VBox paso3BaseDatosYAdmin() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(20));

        Label lbl = new Label("Paso 3: Base de Datos y Administrador Inicial");
        lbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        chkConfigurarDb = new CheckBox("Crear base de datos y usuario MySQL para este proyecto");
        chkConfigurarDb.setSelected(true);

        chkImportarEsquemaBolsa = new CheckBox("Inicializar esquema previo (solo si la base está vacía)");
        chkImportarEsquemaBolsa.setSelected(false);

        txtDbNombre = new TextField();
        txtDbUsuario = new TextField();
        txtDbPassword = new PasswordField();
        txtDbPassword.setText(generarPasswordAleatorio());

        Button btnGenDbPass = new Button("Generar otra clave");
        btnGenDbPass.setOnAction(ev -> txtDbPassword.setText(generarPasswordAleatorio()));

        chkAdoptarDb = new CheckBox("Reutilizar / adoptar base de datos si ya existe");

        txtAdminNombre = new TextField("Administrador General");
        txtAdminEmail = new TextField("admin@empresa.com");
        txtAdminPassword = new PasswordField();
        txtAdminPassword.setText("Admin@" + generarPasswordAleatorio().substring(0, 8));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);

        grid.add(chkConfigurarDb, 0, 0, 2, 1);
        grid.add(chkImportarEsquemaBolsa, 0, 1, 2, 1);

        grid.add(new Label("Nombre Base de Datos:"), 0, 2);
        grid.add(txtDbNombre, 1, 2);

        grid.add(new Label("Usuario MySQL:"), 0, 3);
        grid.add(txtDbUsuario, 1, 3);

        grid.add(new Label("Contraseña MySQL:"), 0, 4);
        HBox boxDbPass = new HBox(8, txtDbPassword, btnGenDbPass);
        grid.add(boxDbPass, 1, 4);

        grid.add(chkAdoptarDb, 1, 5);

        grid.add(new Separator(), 0, 6, 2, 1);

        grid.add(new Label("Nombre Admin Inicial:"), 0, 7);
        grid.add(txtAdminNombre, 1, 7);

        grid.add(new Label("Email Institucional Admin:"), 0, 8);
        grid.add(txtAdminEmail, 1, 8);

        grid.add(new Label("Contraseña Admin:"), 0, 9);
        grid.add(txtAdminPassword, 1, 9);

        // Habilitar/deshabilitar campos según chkConfigurarDb
        chkConfigurarDb.selectedProperty().addListener((obs, old, val) -> {
            txtDbNombre.setDisable(!val);
            txtDbUsuario.setDisable(!val);
            txtDbPassword.setDisable(!val);
            chkAdoptarDb.setDisable(!val);
            chkImportarEsquemaBolsa.setDisable(!val);
        });

        box.getChildren().addAll(lbl, grid);
        return box;
    }

    private VBox paso4DiagnosticoPrevio() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(20));

        Label lbl = new Label("Paso 4: Diagnóstico Previo en el Servidor VPS");
        lbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label desc = new Label("Antes de iniciar el despliegue, realizaremos una comprobación por SSH para validar RAM, disco y servicios.");
        desc.setWrapText(true);

        btnEjecutarDiag = new Button("🩺 Ejecutar Comprobación Previa");
        btnEjecutarDiag.getStyleClass().add("boton-primario");
        btnEjecutarDiag.setOnAction(ev -> ejecutarDiagnosticoPrevio());

        txtResumenDiagPrevio = new TextArea();
        txtResumenDiagPrevio.setEditable(false);
        txtResumenDiagPrevio.setPrefRowCount(8);
        txtResumenDiagPrevio.setPromptText("Presiona el botón para comprobar el estado del servidor...");

        box.getChildren().addAll(lbl, desc, btnEjecutarDiag, txtResumenDiagPrevio);
        return box;
    }

    private VBox paso5Ejecucion() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(20));

        Label lbl = new Label("Paso 5: Ejecución y Monitoreo en Tiempo Real");
        lbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        consola = new ConsolaOperaciones();

        btnIniciarDespliegue = new Button("🚀 Iniciar Despliegue de Producción");
        btnIniciarDespliegue.getStyleClass().add("boton-primario");
        btnIniciarDespliegue.setStyle("-fx-font-size: 14px; -fx-padding: 8px 18px;");
        btnIniciarDespliegue.setOnAction(ev -> iniciarDespliegue());

        box.getChildren().addAll(lbl, btnIniciarDespliegue, consola);
        return box;
    }

    private void ejecutarDiagnosticoPrevio() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) return;

        btnEjecutarDiag.setDisable(true);
        txtResumenDiagPrevio.setText("Conectando por SSH y analizando recursos del servidor...");

        new Thread(() -> {
            try {
                ServerDiagnostic diag = servicios.mantenimiento().diagnosticar(inst, servicios.prompt(), null);
                Platform.runLater(() -> {
                    btnEjecutarDiag.setDisable(false);
                    StringBuilder sb = new StringBuilder();
                    sb.append("SSH Conexión: ").append(diag.sshOk() ? "CORRECTA ✓" : "FALLIDA ✗").append("\n");
                    sb.append("Resumen: ").append(diag.resumen()).append("\n\n");
                    for (DiagnosticCheck c : diag.checks()) {
                        sb.append(String.format("[%s] %-25s: %s\n", c.nivel(), c.nombre(), c.detalle()));
                    }
                    txtResumenDiagPrevio.setText(sb.toString());
                    diagPrevioOk = diag.sshOk();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnEjecutarDiag.setDisable(false);
                    txtResumenDiagPrevio.setText("Error en diagnóstico: " + e.getMessage());
                });
            }
        }, "diag-previo").start();
    }

    private void iniciarDespliegue() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) return;

        btnIniciarDespliegue.setDisable(true);
        btnAnterior.setDisable(true);
        consola.limpiar();

        DeploymentService.Solicitud sol = new DeploymentService.Solicitud(
                inst,
                chkHttps.isSelected(),
                chkRedis.isSelected(),
                chkAdoptarDb.isSelected(),
                txtDbPassword.getText(),
                txtAdminNombre.getText().trim(),
                txtAdminEmail.getText().trim(),
                txtAdminPassword.getText(),
                "",
                "",
                txtRepoGit != null ? txtRepoGit.getText().trim() : "",
                txtRamaGit != null ? txtRamaGit.getText().trim() : "main",
                chkConfigurarDb == null || chkConfigurarDb.isSelected(),
                chkImportarEsquemaBolsa != null && chkImportarEsquemaBolsa.isSelected()
        );

        new Thread(() -> {
            try {
                DeploymentReport reporte = servicios.despliegues().ejecutar(
                        sol,
                        servicios.prompt(),
                        consola.asDeploymentListener()
                );

                Platform.runLater(() -> {
                    btnIniciarDespliegue.setDisable(false);
                    btnAnterior.setDisable(false);
                    ReporteDespliegueDialog dialog = new ReporteDespliegueDialog(reporte);
                    dialog.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnIniciarDespliegue.setDisable(false);
                    btnAnterior.setDisable(false);
                    UiHelper.alertaError("Fallo en el Despliegue", e.getMessage());
                });
            }
        }, "deploy-runner").start();
    }

    private void mostrarPaso(int paso) {
        pasoActual = paso;
        for (int i = 0; i < panelPasos.getChildren().size(); i++) {
            panelPasos.getChildren().get(i).setVisible(i + 1 == paso);
        }
        actualizarIndicadorPasos();
        btnAnterior.setDisable(paso == 1);
        btnSiguiente.setVisible(paso < 5);
    }

    private void avanzarPaso() {
        if (pasoActual == 1) {
            if (cbInstitucion.getValue() == null) {
                UiHelper.alertaAdvertencia("Institución Requerida", null, "Debes seleccionar una institución.");
                return;
            }
        } else if (pasoActual == 2) {
            if (txtDominio.getText().isBlank()) {
                UiHelper.alertaAdvertencia("Dominio Requerido", null, "Ingresa un dominio FQDN válido.");
                return;
            }
        } else if (pasoActual == 3) {
            if (txtDbNombre.getText().isBlank() || txtAdminEmail.getText().isBlank() || txtAdminPassword.getText().isBlank()) {
                UiHelper.alertaAdvertencia("Datos Incompletos", null, "Completa el nombre de base de datos y credenciales del admin.");
                return;
            }
        }
        if (pasoActual < 5) {
            mostrarPaso(pasoActual + 1);
        }
    }

    private void retrocederPaso() {
        if (pasoActual > 1) {
            mostrarPaso(pasoActual - 1);
        }
    }

    public void preseleccionar(Institution inst) {
        alMostrar();
        if (inst != null) {
            cbInstitucion.getItems().stream()
                    .filter(i -> i.getId() == inst.getId())
                    .findFirst()
                    .ifPresent(cbInstitucion::setValue);
        }
        mostrarPaso(1);
    }

    private String generarPasswordAleatorio() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Override
    public Node raiz() {
        return scroll;
    }

    @Override
    public void alMostrar() {
        try {
            List<Institution> lista = servicios.app().instituciones.listar();
            Institution sel = cbInstitucion.getValue();
            cbInstitucion.getItems().setAll(lista);
            if (sel != null) {
                lista.stream().filter(i -> i.getId() == sel.getId()).findFirst().ifPresent(cbInstitucion::setValue);
            }
        } catch (Exception e) {
            log.error("Error al listar instituciones en asistente: {}", e.getMessage(), e);
        }
    }
}

