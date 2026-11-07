package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.OperationRecord;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registro de auditoría y trazabilidad histórica de todas las operaciones ejecutadas.
 */
public class HistorialVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(HistorialVista.class);

    private final Servicios servicios;
    private final VBox contenedor;

    private ComboBox<String> cbTipo;
    private ComboBox<String> cbEstado;
    private TableView<OperationRecord> tabla;
    private TextArea txtDetalleTecnico;

    public HistorialVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1300);

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Historial de Operaciones y Auditoría",
                "Registro inmutable de todas las acciones ejecutadas en servidores e instituciones.");

        // Barra de filtros
        HBox barraFiltros = new HBox(12);
        barraFiltros.setAlignment(Pos.CENTER_LEFT);
        barraFiltros.getStyleClass().add("card");
        barraFiltros.setPadding(new Insets(12));

        Label lblFiltroTipo = new Label("Tipo:");
        cbTipo = new ComboBox<>();
        cbTipo.getItems().addAll("TODOS", "DESPLIEGUE", "ACTUALIZACION", "RESPALDO", "RESTAURACION", "DIAGNOSTICO", "MANTENIMIENTO");
        cbTipo.setValue("TODOS");

        Label lblFiltroEstado = new Label("Estado:");
        cbEstado = new ComboBox<>();
        cbEstado.getItems().addAll("TODOS", "EXITOSA", "FALLIDA", "EN_CURSO", "OMITIDA");
        cbEstado.setValue("TODOS");

        Button btnFiltrar = new Button("Filtrar");
        btnFiltrar.getStyleClass().add("boton-primario");
        btnFiltrar.setOnAction(ev -> alMostrar());

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        barraFiltros.getChildren().addAll(lblFiltroTipo, cbTipo, lblFiltroEstado, cbEstado, btnFiltrar, btnRefrescar);

        // Tabla de operaciones
        tabla = new TableView<>();
        tabla.setPrefHeight(320);
        tabla.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tabla, Priority.ALWAYS);

        TableColumn<OperationRecord, String> colId = new TableColumn<>("ID");
        colId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getId())));
        colId.setMaxWidth(60);

        TableColumn<OperationRecord, String> colTipo = new TableColumn<>("Tipo");
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipo()));
        colTipo.setMaxWidth(130);

        TableColumn<OperationRecord, String> colInst = new TableColumn<>("Institución");
        colInst.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getInstitucion()));

        TableColumn<OperationRecord, String> colServidor = new TableColumn<>("Servidor");
        colServidor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getServidor()));

        TableColumn<OperationRecord, Node> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(c -> {
            String st = c.getValue().getEstado();
            String clase = "EXITOSA".equalsIgnoreCase(st) ? "exito" : ("FALLIDA".equalsIgnoreCase(st) ? "error" : "en-curso");
            return new javafx.beans.property.SimpleObjectProperty<>(UiHelper.badge(st, clase));
        });
        colEstado.setMaxWidth(110);

        TableColumn<OperationRecord, String> colInicio = new TableColumn<>("Inicio");
        colInicio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getInicioEn()));
        colInicio.setMaxWidth(150);

        TableColumn<OperationRecord, String> colFin = new TableColumn<>("Fin");
        colFin.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFinEn() == null ? "-" : c.getValue().getFinEn()));
        colFin.setMaxWidth(150);

        TableColumn<OperationRecord, String> colDetalle = new TableColumn<>("Detalle");
        colDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDetalle()));

        tabla.getColumns().addAll(colId, colTipo, colInst, colServidor, colEstado, colInicio, colFin, colDetalle);

        // Panel inferior con detalle técnico
        VBox panelDetalle = new VBox(8);
        HBox cabeceraDetalle = new HBox(12);
        cabeceraDetalle.setAlignment(Pos.CENTER_LEFT);

        Label lblDetalle = new Label("Detalle Técnico / Salida del Proceso:");
        lblDetalle.setStyle("-fx-font-weight: bold;");

        Button btnCopiar = new Button("Copiar Salida");
        btnCopiar.getStyleClass().add("boton-pequeno");
        btnCopiar.setOnAction(ev -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(txtDetalleTecnico.getText());
            Clipboard.getSystemClipboard().setContent(content);
            btnCopiar.setText("¡Copiado!");
        });

        cabeceraDetalle.getChildren().addAll(lblDetalle, btnCopiar);

        txtDetalleTecnico = new TextArea();
        txtDetalleTecnico.setEditable(false);
        txtDetalleTecnico.setWrapText(true);
        txtDetalleTecnico.setPrefRowCount(7);
        txtDetalleTecnico.getStyleClass().add("terminal-area");

        tabla.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                txtDetalleTecnico.setText(sel.getTecnico() == null || sel.getTecnico().isBlank()
                        ? "(Sin salida técnica registrada)" : sel.getTecnico());
                btnCopiar.setText("Copiar Salida");
            } else {
                txtDetalleTecnico.clear();
            }
        });

        panelDetalle.getChildren().addAll(cabeceraDetalle, txtDetalleTecnico);

        contenedor.getChildren().addAll(cabecera, barraFiltros, tabla, panelDetalle);
    }

    @Override
    public Node raiz() {
        return contenedor;
    }

    @Override
    public void alMostrar() {
        try {
            String tipo = cbTipo.getValue();
            if ("TODOS".equals(tipo)) tipo = null;
            String estado = cbEstado.getValue();
            if ("TODOS".equals(estado)) estado = null;

            List<OperationRecord> lista = servicios.app().operaciones.filtrar(tipo, estado, 100);
            Platform.runLater(() -> tabla.getItems().setAll(lista));
        } catch (Exception e) {
            log.error("Error al filtrar historial: {}", e.getMessage(), e);
        }
    }
}
