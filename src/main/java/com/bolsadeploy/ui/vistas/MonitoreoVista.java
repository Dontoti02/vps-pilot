package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.CheckLevel;
import com.bolsadeploy.domain.DiagnosticCheck;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.ServerDiagnostic;
import com.bolsadeploy.monitoring.MonitorService;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Vista de monitoreo en tiempo real: diagnóstico por SSH (servicios, CPU, RAM, disco)
 * y comprobación externa HTTP/TLS con días de vigencia de certificados.
 */
public class MonitoreoVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(MonitoreoVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    private ComboBox<Institution> cbInstitucion;
    private Button btnDiagnosticar;
    private Button btnComprobarWeb;

    private Label lblResumenSsh;
    private Label lblResumenWeb;

    private TableView<DiagnosticCheck> tablaChecks;

    public MonitoreoVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1200);

        scroll = new ScrollPane(contenedor);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Monitoreo y Salud de Infraestructura",
                "Comprobación en vivo del estado del servidor, uso de recursos, servicios Nginx/PHP/MySQL y disponibilidad web.");

        // Barra de control
        HBox barraControl = new HBox(12);
        barraControl.setAlignment(Pos.CENTER_LEFT);
        barraControl.getStyleClass().add("card");
        barraControl.setPadding(new Insets(14));

        Label lblInst = new Label("Institución:");
        lblInst.setStyle("-fx-font-weight: bold;");

        cbInstitucion = new ComboBox<>();
        cbInstitucion.setPrefWidth(280);
        cbInstitucion.setPromptText("Selecciona una institución...");

        btnDiagnosticar = new Button("🩺 Diagnosticar Servidor (SSH)");
        btnDiagnosticar.getStyleClass().add("boton-primario");
        btnDiagnosticar.setOnAction(ev -> ejecutarDiagnosticoSsh());

        btnComprobarWeb = new Button("🌐 Comprobar Sitio Web (HTTP/TLS)");
        btnComprobarWeb.setOnAction(ev -> ejecutarComprobacionWeb());

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        barraControl.getChildren().addAll(lblInst, cbInstitucion, btnDiagnosticar, btnComprobarWeb, btnRefrescar);

        // Tarjetas de resumen
        HBox panelResumen = new HBox(16);
        VBox cardSsh = new VBox(6);
        cardSsh.getStyleClass().addAll("card", "tarjeta-metrica");
        HBox.setHgrow(cardSsh, Priority.ALWAYS);
        Label lblTitSsh = new Label("ESTADO DEL SERVIDOR (SSH)");
        lblTitSsh.getStyleClass().add("metrica-titulo");
        lblResumenSsh = new Label("Sin diagnóstico reciente");
        lblResumenSsh.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        cardSsh.getChildren().addAll(lblTitSsh, lblResumenSsh);

        VBox cardWeb = new VBox(6);
        cardWeb.getStyleClass().addAll("card", "tarjeta-metrica");
        HBox.setHgrow(cardWeb, Priority.ALWAYS);
        Label lblTitWeb = new Label("DISPONIBILIDAD WEB (HTTP/TLS)");
        lblTitWeb.getStyleClass().add("metrica-titulo");
        lblResumenWeb = new Label("Sin comprobación web reciente");
        lblResumenWeb.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        cardWeb.getChildren().addAll(lblTitWeb, lblResumenWeb);

        panelResumen.getChildren().addAll(cardSsh, cardWeb);

        // Tabla de verificaciones
        VBox secChecks = new VBox(8);
        Label lblChecksTitulo = new Label("Hallazgos de la Inspección del Servidor:");
        lblChecksTitulo.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        tablaChecks = new TableView<>();
        tablaChecks.setPrefHeight(380);
        tablaChecks.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DiagnosticCheck, Node> colNivel = new TableColumn<>("Nivel");
        colNivel.setCellValueFactory(c -> {
            CheckLevel lvl = c.getValue().nivel();
            String clase = switch (lvl) {
                case CORRECTO -> "exito";
                case ADVERTENCIA -> "advertencia";
                case ERROR -> "error";
                default -> "pendiente";
            };
            return new javafx.beans.property.SimpleObjectProperty<>(UiHelper.badge(lvl.name(), clase));
        });
        colNivel.setMaxWidth(110);

        TableColumn<DiagnosticCheck, String> colComponente = new TableColumn<>("Componente / Métrica");
        colComponente.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombre()));
        colComponente.setPrefWidth(220);

        TableColumn<DiagnosticCheck, String> colDetalle = new TableColumn<>("Valor Medido / Diagnóstico");
        colDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detalle()));
        colDetalle.setPrefWidth(550);

        tablaChecks.getColumns().addAll(colNivel, colComponente, colDetalle);
        secChecks.getChildren().addAll(lblChecksTitulo, tablaChecks);

        contenedor.getChildren().addAll(cabecera, barraControl, panelResumen, secChecks);
    }

    private void ejecutarDiagnosticoSsh() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) {
            UiHelper.alertaAdvertencia("Selección Requerida", null, "Selecciona una institución para diagnosticar su servidor.");
            return;
        }

        btnDiagnosticar.setDisable(true);
        lblResumenSsh.setText("Conectando por SSH y recolectando telemetría...");

        new Thread(() -> {
            try {
                ServerDiagnostic diag = servicios.mantenimiento().diagnosticar(inst, servicios.prompt(), null);
                Platform.runLater(() -> {
                    btnDiagnosticar.setDisable(false);
                    lblResumenSsh.setText(diag.resumen());
                    tablaChecks.getItems().setAll(diag.checks());
                    if (diag.sinErrores()) {
                        UiHelper.alertaExito("Diagnóstico Satisfactorio", "El servidor se encuentra operando normalmente.\n" + diag.resumen());
                    } else {
                        UiHelper.alertaAdvertencia("Atención Requerida", "Se detectaron problemas en el servidor", diag.resumen());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnDiagnosticar.setDisable(false);
                    lblResumenSsh.setText("Error: " + e.getMessage());
                    UiHelper.alertaError("Error de Diagnóstico", e.getMessage());
                });
            }
        }, "monitor-ssh").start();
    }

    private void ejecutarComprobacionWeb() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) {
            UiHelper.alertaAdvertencia("Selección Requerida", null, "Selecciona una institución para comprobar su web.");
            return;
        }

        btnComprobarWeb.setDisable(true);
        lblResumenWeb.setText("Comprobando disponibilidad pública...");

        new Thread(() -> {
            try {
                MonitorService.HttpCheck check = servicios.mantenimiento().comprobarWeb(inst);
                Platform.runLater(() -> {
                    btnComprobarWeb.setDisable(false);
                    String sslInfo = check.diasCertificado() >= 0
                            ? " (SSL vence en " + check.diasCertificado() + " días)"
                            : " (Sin SSL válido)";
                    String texto = String.format("HTTP %d en %d ms%s", check.codigo(), check.ms(), sslInfo);
                    lblResumenWeb.setText(texto);

                    if (check.ok()) {
                        UiHelper.alertaExito("Sitio Operativo", "El sitio web respondió correctamente:\n" + texto + "\n" + check.detalle());
                    } else {
                        UiHelper.alertaAdvertencia("Fallo en Sitio Web", "El sitio web no respondió satisfactoriamente", texto + "\n" + check.detalle());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnComprobarWeb.setDisable(false);
                    lblResumenWeb.setText("Error: " + e.getMessage());
                    UiHelper.alertaError("Error HTTP", e.getMessage());
                });
            }
        }, "monitor-web").start();
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
            log.error("Error al refrescar instituciones en monitoreo: {}", e.getMessage(), e);
        }
    }
}
