package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.InstitutionStatus;
import com.bolsadeploy.domain.OperationRecord;
import com.bolsadeploy.domain.VpsServer;
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

import java.awt.Desktop;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel de control principal con resumen ejecutivo, indicadores y accesos rápidos.
 */
public class DashboardVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(DashboardVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    private VBox cardTotalInst;
    private VBox cardTotalVps;
    private VBox cardInstActivas;
    private VBox cardActualizaciones;

    private TableView<Institution> tablaInstituciones;
    private TableView<OperationRecord> tablaOperaciones;

    private final Map<Long, String> vpsNombres = new HashMap<>();

    public DashboardVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(20);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1300);

        scroll = new ScrollPane(contenedor);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Dashboard Ejecutivo",
                "Monitoreo centralizado del ecosistema de bolsas laborales y servidores VPS.");

        // Tarjetas métricas superiores
        HBox metricas = new HBox(16);
        cardTotalInst = UiHelper.tarjetaMetrica("Instituciones", "0", "Total registradas", "#3b82f6");
        cardTotalVps = UiHelper.tarjetaMetrica("Servidores VPS", "0", "Activos en el sistema", "#10b981");
        cardInstActivas = UiHelper.tarjetaMetrica("Instalaciones Exitosas", "0", "Operando en producción", "#8b5cf6");
        cardActualizaciones = UiHelper.tarjetaMetrica("Operaciones", "0", "Historial registrado", "#f59e0b");
        metricas.getChildren().addAll(cardTotalInst, cardTotalVps, cardInstActivas, cardActualizaciones);

        // Barra de acciones rápidas
        HBox accionesRapidas = new HBox(12);
        accionesRapidas.setAlignment(Pos.CENTER_LEFT);
        accionesRapidas.setPadding(new Insets(8, 0, 8, 0));

        Label lblAcciones = new Label("Accesos Rápidos:");
        lblAcciones.setStyle("-fx-font-weight: bold;");

        Button btnNuevoDespliegue = new Button("+ Nueva Instalación");
        btnNuevoDespliegue.getStyleClass().add("boton-primario");
        btnNuevoDespliegue.setOnAction(ev -> servicios.mainApp().navegarA("Nuevo despliegue"));

        Button btnActualizar = new Button("🔄 Ver Actualizaciones");
        btnActualizar.setOnAction(ev -> servicios.mainApp().navegarA("Actualizaciones"));

        Button btnMonitoreo = new Button("📊 Estado de Monitoreo");
        btnMonitoreo.setOnAction(ev -> servicios.mainApp().navegarA("Monitoreo"));

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        accionesRapidas.getChildren().addAll(lblAcciones, btnNuevoDespliegue, btnActualizar, btnMonitoreo, btnRefrescar);

        // Tabla de Instituciones
        VBox secInst = new VBox(8);
        Label lblTituloInst = new Label("Instituciones Desplegadas");
        lblTituloInst.getStyleClass().add("subtitulo-seccion");

        tablaInstituciones = new TableView<>();
        tablaInstituciones.setPrefHeight(220);
        tablaInstituciones.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Institution, String> colNombre = new TableColumn<>("Institución");
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));

        TableColumn<Institution, String> colDominio = new TableColumn<>("Dominio");
        colDominio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDominio()));

        TableColumn<Institution, String> colVps = new TableColumn<>("VPS");
        colVps.setCellValueFactory(c -> new SimpleStringProperty(
                vpsNombres.getOrDefault(c.getValue().getVpsId(), "VPS #" + c.getValue().getVpsId())));

        TableColumn<Institution, Node> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(c -> {
            InstitutionStatus st = c.getValue().getEstado();
            String clase = switch (st) {
                case INSTALADA -> "exito";
                case INSTALANDO, ACTUALIZANDO -> "en-curso";
                case ERROR -> "error";
                default -> "pendiente";
            };
            return new javafx.beans.property.SimpleObjectProperty<>(UiHelper.badge(st.name(), clase));
        });

        TableColumn<Institution, String> colCommit = new TableColumn<>("Commit Desplegado");
        colCommit.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCommitDesplegado() == null || c.getValue().getCommitDesplegado().isBlank()
                        ? "-" : c.getValue().getCommitDesplegado()));

        TableColumn<Institution, Void> colAccion = new TableColumn<>("Acceso");
        colAccion.setCellFactory(param -> new TableCell<>() {
            private final Button btn = new Button("Abrir Web");
            {
                btn.getStyleClass().add("boton-pequeno");
                btn.setOnAction(ev -> {
                    Institution inst = getTableView().getItems().get(getIndex());
                    abrirUrl(inst.urlBase());
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });

        tablaInstituciones.getColumns().addAll(colNombre, colDominio, colVps, colEstado, colCommit, colAccion);
        secInst.getChildren().addAll(lblTituloInst, tablaInstituciones);

        // Tabla de operaciones recientes
        VBox secOp = new VBox(8);
        Label lblTituloOp = new Label("Últimas Operaciones Realizadas");
        lblTituloOp.getStyleClass().add("subtitulo-seccion");

        tablaOperaciones = new TableView<>();
        tablaOperaciones.setPrefHeight(180);
        tablaOperaciones.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<OperationRecord, String> colOpId = new TableColumn<>("ID");
        colOpId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getId())));
        colOpId.setMaxWidth(60);

        TableColumn<OperationRecord, String> colOpTipo = new TableColumn<>("Tipo");
        colOpTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipo()));

        TableColumn<OperationRecord, String> colOpInst = new TableColumn<>("Institución");
        colOpInst.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getInstitucion()));

        TableColumn<OperationRecord, Node> colOpEstado = new TableColumn<>("Estado");
        colOpEstado.setCellValueFactory(c -> {
            String est = c.getValue().getEstado();
            String clase = "EXITOSA".equalsIgnoreCase(est) ? "exito" : ("FALLIDA".equalsIgnoreCase(est) ? "error" : "en-curso");
            return new javafx.beans.property.SimpleObjectProperty<>(UiHelper.badge(est, clase));
        });

        TableColumn<OperationRecord, String> colOpInicio = new TableColumn<>("Fecha");
        colOpInicio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getInicioEn()));

        TableColumn<OperationRecord, String> colOpDetalle = new TableColumn<>("Detalle");
        colOpDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDetalle()));

        tablaOperaciones.getColumns().addAll(colOpId, colOpTipo, colOpInst, colOpEstado, colOpInicio, colOpDetalle);
        secOp.getChildren().addAll(lblTituloOp, tablaOperaciones);

        contenedor.getChildren().addAll(cabecera, metricas, accionesRapidas, secInst, secOp);
    }

    private void abrirUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            log.error("No se pudo abrir el navegador: {}", e.getMessage());
        }
    }

    @Override
    public Node raiz() {
        return scroll;
    }

    @Override
    public void alMostrar() {
        try {
            List<VpsServer> listaVps = servicios.app().vps.listar();
            vpsNombres.clear();
            for (VpsServer v : listaVps) {
                vpsNombres.put(v.getId(), v.getNombre());
            }

            List<Institution> listaInst = servicios.app().instituciones.listar();
            List<OperationRecord> listaOps = servicios.app().operaciones.listar(15);

            long exitosas = listaInst.stream().filter(i -> i.getEstado() == InstitutionStatus.INSTALADA).count();

            Platform.runLater(() -> {
                actualizarMetrica(cardTotalInst, String.valueOf(listaInst.size()));
                actualizarMetrica(cardTotalVps, String.valueOf(listaVps.size()));
                actualizarMetrica(cardInstActivas, String.valueOf(exitosas));
                actualizarMetrica(cardActualizaciones, String.valueOf(listaOps.size()));

                tablaInstituciones.getItems().setAll(listaInst);
                tablaOperaciones.getItems().setAll(listaOps);
            });
        } catch (Exception e) {
            log.error("Error al refrescar dashboard: {}", e.getMessage(), e);
        }
    }

    private void actualizarMetrica(VBox card, String valor) {
        if (card.getChildren().size() > 1 && card.getChildren().get(1) instanceof Label lbl) {
            lbl.setText(valor);
        }
    }
}
