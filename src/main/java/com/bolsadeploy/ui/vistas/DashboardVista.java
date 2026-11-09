package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.BackupRecord;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.InstitutionStatus;
import com.bolsadeploy.domain.OperationRecord;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.*;

/**
 * Panel de control principal con analíticas avanzadas, métricas en tiempo real,
 * gráficos de distribución, monitoreo de salud del ecosistema y accesos rápidos.
 */
public class DashboardVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(DashboardVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    // Banner inteligente de salud
    private HBox bannerSalud;
    private Label lblBannerIcono;
    private Label lblBannerTitulo;
    private Label lblBannerDesc;
    private HBox bannerAcciones;

    // Tarjetas KPIs superiores
    private VBox cardTotalInst;
    private VBox cardTotalVps;
    private VBox cardTasaExito;
    private VBox cardTotalBackups;

    // Analíticas: Gráfico de distribución de proyectos
    private PieChart chartDistribucion;
    private Label lblPillProd;
    private Label lblPillCurso;
    private Label lblPillError;
    private Label lblPillPend;

    // Analíticas: Rendimiento y desglose de operaciones
    private Label lblPorcentajeExito;
    private ProgressBar pbTasaExito;
    private Label lblBadgeConfiabilidad;
    private Label lblConteoOps;

    // Barras de progreso de desglose por tipo
    private ProgressBar pbOpDespliegues;
    private Label lblOpDespliegues;
    private ProgressBar pbOpActualizaciones;
    private Label lblOpActualizaciones;
    private ProgressBar pbOpRespaldos;
    private Label lblOpRespaldos;
    private ProgressBar pbOpOtros;
    private Label lblOpOtros;

    // Última actividad
    private Label lblUltimaActividad;
    private Label lblUltimaActividadEstado;

    // Carga de flota VPS
    private VBox contenedorFlotaVps;

    // Tablas y datos observables
    private final ObservableList<Institution> listaMasterInst = FXCollections.observableArrayList();
    private FilteredList<Institution> listaFiltradaInst;
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
        VBox cabecera = UiHelper.cabeceraSeccion("Dashboard Operativo y Analíticas",
                "Métricas en tiempo real, distribución del ecosistema, auditoría y salud de despliegues en VPS.");

        // ==========================================================
        // 1. Barra de Accesos Rápidos Superior
        // ==========================================================
        HBox accionesRapidas = new HBox(12);
        accionesRapidas.setAlignment(Pos.CENTER_LEFT);
        accionesRapidas.setPadding(new Insets(4, 0, 8, 0));

        Label lblAcciones = new Label("Acciones:");
        lblAcciones.setStyle("-fx-font-weight: bold; -fx-text-fill: #94a3b8;");

        Button btnNuevoDespliegue = new Button("🚀 + Nuevo Despliegue");
        btnNuevoDespliegue.getStyleClass().add("boton-primario");
        btnNuevoDespliegue.setOnAction(ev -> servicios.mainApp().navegarA("Nuevo despliegue"));

        Button btnActualizar = new Button("🔄 Actualizaciones");
        btnActualizar.setOnAction(ev -> servicios.mainApp().navegarA("Actualizaciones"));

        Button btnMonitoreo = new Button("📊 Monitoreo VPS");
        btnMonitoreo.setOnAction(ev -> servicios.mainApp().navegarA("Monitoreo"));

        Button btnCopias = new Button("💾 Copias de Seguridad");
        btnCopias.setOnAction(ev -> servicios.mainApp().navegarA("Copias de seguridad"));

        Region espaciadorAcciones = new Region();
        HBox.setHgrow(espaciadorAcciones, Priority.ALWAYS);

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        accionesRapidas.getChildren().addAll(lblAcciones, btnNuevoDespliegue, btnActualizar, btnMonitoreo, btnCopias,
                espaciadorAcciones, btnRefrescar);

        // ==========================================================
        // 2. Banner Inteligente de Salud del Ecosistema
        // ==========================================================
        bannerSalud = new HBox(16);
        bannerSalud.setAlignment(Pos.CENTER_LEFT);
        bannerSalud.getStyleClass().add("banner-salud-ok");

        lblBannerIcono = new Label("🛡️");
        lblBannerIcono.setStyle("-fx-font-size: 28px;");

        VBox bannerTexto = new VBox(2);
        lblBannerTitulo = new Label("Ecosistema 100% Operativo y Saludable");
        lblBannerTitulo.getStyleClass().add("banner-titulo-ok");

        lblBannerDesc = new Label("Todos los proyectos activos responden correctamente y no se detectan fallos críticos.");
        lblBannerDesc.getStyleClass().add("banner-desc");
        lblBannerDesc.setWrapText(true);
        bannerTexto.getChildren().addAll(lblBannerTitulo, lblBannerDesc);
        HBox.setHgrow(bannerTexto, Priority.ALWAYS);

        bannerAcciones = new HBox(8);
        bannerAcciones.setAlignment(Pos.CENTER_RIGHT);
        Label badgeOk = UiHelper.badge("SISTEMA ESTABLE", "exito");
        bannerAcciones.getChildren().add(badgeOk);

        bannerSalud.getChildren().addAll(lblBannerIcono, bannerTexto, bannerAcciones);

        // ==========================================================
        // 3. Tarjetas KPIs Ejecutivas (4 Métricas Clave)
        // ==========================================================
        HBox metricas = new HBox(16);
        cardTotalInst = UiHelper.tarjetaMetrica("Proyectos / Sitios", "0", "0 en producción", "#3b82f6");
        cardTotalVps = UiHelper.tarjetaMetrica("Servidores VPS", "0", "Conectados y activos", "#10b981");
        cardTasaExito = UiHelper.tarjetaMetrica("Tasa de Éxito", "100%", "Confiabilidad en operaciones", "#8b5cf6");
        cardTotalBackups = UiHelper.tarjetaMetrica("Respaldos Locales", "0 copias", "0 MB asegurados", "#f59e0b");
        metricas.getChildren().addAll(cardTotalInst, cardTotalVps, cardTasaExito, cardTotalBackups);

        // ==========================================================
        // 4. Panel Analítico Central (2 Tarjetas Gráficas Grandes)
        // ==========================================================
        HBox panelAnaliticas = new HBox(16);

        // --- Card Izquierda: Gráfico de Distribución del Ecosistema ---
        VBox cardGrafico = new VBox(12);
        cardGrafico.getStyleClass().add("analytics-card");
        HBox.setHgrow(cardGrafico, Priority.ALWAYS);

        VBox headGrafico = new VBox(2);
        Label lblTitGrafico = new Label("Distribución del Ecosistema");
        lblTitGrafico.getStyleClass().add("analytics-titulo");
        Label lblSubGrafico = new Label("Proporción de proyectos según su salud operativa");
        lblSubGrafico.getStyleClass().add("analytics-subtitulo");
        headGrafico.getChildren().addAll(lblTitGrafico, lblSubGrafico);

        chartDistribucion = new PieChart();
        chartDistribucion.setPrefHeight(230);
        chartDistribucion.setMaxHeight(250);
        chartDistribucion.setLabelsVisible(true);
        chartDistribucion.setLegendVisible(true);
        chartDistribucion.setClockwise(true);

        FlowPane pillsResumen = new FlowPane(8, 8);
        pillsResumen.setAlignment(Pos.CENTER);
        lblPillProd = new Label("● En Producción: 0");
        lblPillProd.getStyleClass().add("analytics-pill");
        lblPillProd.setStyle("-fx-border-color: #10b981; -fx-text-fill: #34d399;");

        lblPillCurso = new Label("● En Despliegue: 0");
        lblPillCurso.getStyleClass().add("analytics-pill");
        lblPillCurso.setStyle("-fx-border-color: #6366f1; -fx-text-fill: #a5b4fc;");

        lblPillError = new Label("● Con Errores: 0");
        lblPillError.getStyleClass().add("analytics-pill");
        lblPillError.setStyle("-fx-border-color: #ef4444; -fx-text-fill: #f87171;");

        lblPillPend = new Label("● Pendientes: 0");
        lblPillPend.getStyleClass().add("analytics-pill");
        lblPillPend.setStyle("-fx-border-color: #f59e0b; -fx-text-fill: #fbbf24;");

        pillsResumen.getChildren().addAll(lblPillProd, lblPillCurso, lblPillError, lblPillPend);
        cardGrafico.getChildren().addAll(headGrafico, chartDistribucion, pillsResumen);

        // --- Card Derecha: Rendimiento y Desglose Operativo ---
        VBox cardOperaciones = new VBox(12);
        cardOperaciones.getStyleClass().add("analytics-card");
        HBox.setHgrow(cardOperaciones, Priority.ALWAYS);

        VBox headOps = new VBox(2);
        Label lblTitOps = new Label("Rendimiento de Operaciones");
        lblTitOps.getStyleClass().add("analytics-titulo");
        Label lblSubOps = new Label("Confiabilidad histórica y desglose por tipo de tarea");
        lblSubOps.getStyleClass().add("analytics-subtitulo");
        headOps.getChildren().addAll(lblTitOps, lblSubOps);

        // Indicador de Confiabilidad
        HBox boxConfiabilidad = new HBox(12);
        boxConfiabilidad.setAlignment(Pos.CENTER_LEFT);
        boxConfiabilidad.setPadding(new Insets(4, 0, 6, 0));

        lblPorcentajeExito = new Label("100.0%");
        lblPorcentajeExito.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #34d399;");

        VBox boxBarraConfiabilidad = new VBox(4);
        HBox.setHgrow(boxBarraConfiabilidad, Priority.ALWAYS);
        pbTasaExito = new ProgressBar(1.0);
        pbTasaExito.setMaxWidth(Double.MAX_VALUE);
        pbTasaExito.setStyle("-fx-accent: #10b981;");

        lblConteoOps = new Label("0 exitosas de 0 operaciones registradas");
        lblConteoOps.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        boxBarraConfiabilidad.getChildren().addAll(pbTasaExito, lblConteoOps);

        lblBadgeConfiabilidad = UiHelper.badge("ALTA", "exito");
        boxConfiabilidad.getChildren().addAll(lblPorcentajeExito, boxBarraConfiabilidad, lblBadgeConfiabilidad);

        // Desglose de Operaciones por Tipo (Barras métricas proporcionales)
        VBox boxDesglose = new VBox(8);
        boxDesglose.setPadding(new Insets(6, 0, 6, 0));

        // Despliegues
        pbOpDespliegues = new ProgressBar(0);
        pbOpDespliegues.setMaxWidth(Double.MAX_VALUE);
        pbOpDespliegues.setStyle("-fx-accent: #3b82f6;");
        lblOpDespliegues = new Label("0");
        HBox filaDespliegue = crearFilaMetricaOperacion("🚀 Despliegues Iniciales:", pbOpDespliegues, lblOpDespliegues);

        // Actualizaciones
        pbOpActualizaciones = new ProgressBar(0);
        pbOpActualizaciones.setMaxWidth(Double.MAX_VALUE);
        pbOpActualizaciones.setStyle("-fx-accent: #8b5cf6;");
        lblOpActualizaciones = new Label("0");
        HBox filaActualizacion = crearFilaMetricaOperacion("🔄 Actualizaciones de Código:", pbOpActualizaciones, lblOpActualizaciones);

        // Respaldos
        pbOpRespaldos = new ProgressBar(0);
        pbOpRespaldos.setMaxWidth(Double.MAX_VALUE);
        pbOpRespaldos.setStyle("-fx-accent: #f59e0b;");
        lblOpRespaldos = new Label("0");
        HBox filaRespaldos = crearFilaMetricaOperacion("💾 Respaldos de Seguridad:", pbOpRespaldos, lblOpRespaldos);

        // Otros (Rollback, Mantenimiento)
        pbOpOtros = new ProgressBar(0);
        pbOpOtros.setMaxWidth(Double.MAX_VALUE);
        pbOpOtros.setStyle("-fx-accent: #06b6d4;");
        lblOpOtros = new Label("0");
        HBox filaOtros = crearFilaMetricaOperacion("🩺 Diagnóstico y Otros:", pbOpOtros, lblOpOtros);

        boxDesglose.getChildren().addAll(filaDespliegue, filaActualizacion, filaRespaldos, filaOtros);

        // Caja informativa de última actividad
        HBox boxUltima = new HBox(10);
        boxUltima.setAlignment(Pos.CENTER_LEFT);
        boxUltima.setStyle("-fx-background-color: #12151c; -fx-padding: 8px 12px; -fx-background-radius: 6px; -fx-border-color: #232938; -fx-border-radius: 6px;");

        Label lblTagUltima = new Label("Última actividad:");
        lblTagUltima.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #94a3b8;");

        lblUltimaActividad = new Label("Sin operaciones registradas");
        lblUltimaActividad.setStyle("-fx-font-size: 11px; -fx-text-fill: #cbd5e1;");
        HBox.setHgrow(lblUltimaActividad, Priority.ALWAYS);

        lblUltimaActividadEstado = UiHelper.badge("INACTIVO", "pendiente");
        boxUltima.getChildren().addAll(lblTagUltima, lblUltimaActividad, lblUltimaActividadEstado);

        cardOperaciones.getChildren().addAll(headOps, boxConfiabilidad, boxDesglose, boxUltima);

        panelAnaliticas.getChildren().addAll(cardGrafico, cardOperaciones);

        // ==========================================================
        // 5. Carga y Distribución de Flota VPS
        // ==========================================================
        contenedorFlotaVps = new VBox(8);
        contenedorFlotaVps.getStyleClass().add("analytics-card");

        Label lblTitFlota = new Label("Distribución de Carga en Servidores VPS");
        lblTitFlota.getStyleClass().add("analytics-titulo");
        Label lblSubFlota = new Label("Capacidad y proyectos desplegados por cada nodo VPS conectado");
        lblSubFlota.getStyleClass().add("analytics-subtitulo");
        contenedorFlotaVps.getChildren().addAll(lblTitFlota, lblSubFlota);

        // ==========================================================
        // 6. Tabla de Instituciones / Proyectos con Búsqueda en Vivo
        // ==========================================================
        VBox secInst = new VBox(8);

        HBox barraHeaderInst = new HBox(12);
        barraHeaderInst.setAlignment(Pos.CENTER_LEFT);

        Label lblTituloInst = new Label("Proyectos y Sitios Desplegados");
        lblTituloInst.getStyleClass().add("subtitulo-seccion");
        HBox.setHgrow(lblTituloInst, Priority.ALWAYS);

        TextField txtBuscarInst = new TextField();
        txtBuscarInst.setPromptText("🔍 Filtrar por nombre, dominio o VPS...");
        txtBuscarInst.setPrefWidth(280);
        txtBuscarInst.getStyleClass().add("search-field");

        barraHeaderInst.getChildren().addAll(lblTituloInst, txtBuscarInst);

        tablaInstituciones = new TableView<>();
        tablaInstituciones.setPrefHeight(230);
        tablaInstituciones.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        listaFiltradaInst = new FilteredList<>(listaMasterInst, p -> true);
        txtBuscarInst.textProperty().addListener((obs, oldVal, newVal) -> {
            listaFiltradaInst.setPredicate(inst -> {
                if (newVal == null || newVal.isBlank()) {
                    return true;
                }
                String filtro = newVal.toLowerCase().trim();
                String vps = vpsNombres.getOrDefault(inst.getVpsId(), "").toLowerCase();
                return inst.getNombre().toLowerCase().contains(filtro)
                        || inst.getDominio().toLowerCase().contains(filtro)
                        || inst.getRepoGit().toLowerCase().contains(filtro)
                        || vps.contains(filtro);
            });
        });
        SortedList<Institution> listaOrdenadaInst = new SortedList<>(listaFiltradaInst);
        listaOrdenadaInst.comparatorProperty().bind(tablaInstituciones.comparatorProperty());
        tablaInstituciones.setItems(listaOrdenadaInst);

        TableColumn<Institution, String> colNombre = new TableColumn<>("Proyecto / Institución");
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));

        TableColumn<Institution, String> colDominio = new TableColumn<>("Dominio");
        colDominio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDominio()));

        TableColumn<Institution, String> colVps = new TableColumn<>("VPS");
        colVps.setCellValueFactory(c -> new SimpleStringProperty(
                vpsNombres.getOrDefault(c.getValue().getVpsId(), "VPS #" + c.getValue().getVpsId())));

        TableColumn<Institution, String> colRepo = new TableColumn<>("Repositorio / Rama");
        colRepo.setCellValueFactory(c -> {
            String repo = c.getValue().getRepoGit();
            if (repo == null || repo.isBlank()) {
                repo = "Por defecto";
            } else if (repo.contains("/")) {
                String[] p = repo.split("/");
                repo = p[p.length - 1].replace(".git", "");
            }
            String rama = c.getValue().getRama() == null || c.getValue().getRama().isBlank() ? "main" : c.getValue().getRama();
            return new SimpleStringProperty(repo + " (" + rama + ")");
        });

        TableColumn<Institution, Node> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(c -> {
            InstitutionStatus st = c.getValue().getEstado();
            String clase = switch (st) {
                case INSTALADA -> "exito";
                case INSTALANDO, ACTUALIZANDO -> "en-curso";
                case ERROR -> "error";
                default -> "pendiente";
            };
            return new SimpleObjectProperty<>(UiHelper.badge(st.name(), clase));
        });

        TableColumn<Institution, String> colCommit = new TableColumn<>("Commit");
        colCommit.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCommitDesplegado() == null || c.getValue().getCommitDesplegado().isBlank()
                        ? "-" : c.getValue().getCommitDesplegado()));
        colCommit.setMaxWidth(100);

        TableColumn<Institution, Void> colAccion = new TableColumn<>("Acciones");
        colAccion.setCellFactory(param -> new TableCell<>() {
            private final Button btnAbrir = new Button("🌐 Abrir");
            private final HBox box = new HBox(6, btnAbrir);
            {
                btnAbrir.getStyleClass().add("boton-pequeno");
                btnAbrir.setOnAction(ev -> {
                    Institution inst = getTableView().getItems().get(getIndex());
                    abrirUrl(inst.urlBase());
                });
                box.setAlignment(Pos.CENTER);
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
        colAccion.setMaxWidth(110);

        tablaInstituciones.getColumns().addAll(colNombre, colDominio, colVps, colRepo, colEstado, colCommit, colAccion);
        secInst.getChildren().addAll(barraHeaderInst, tablaInstituciones);

        // ==========================================================
        // 7. Tabla de Operaciones Recientes
        // ==========================================================
        VBox secOp = new VBox(8);

        HBox barraHeaderOp = new HBox(12);
        barraHeaderOp.setAlignment(Pos.CENTER_LEFT);

        Label lblTituloOp = new Label("Registro Reciente de Operaciones y Auditoría");
        lblTituloOp.getStyleClass().add("subtitulo-seccion");
        HBox.setHgrow(lblTituloOp, Priority.ALWAYS);

        Button btnVerTodoHistorial = new Button("Ver Todo el Historial →");
        btnVerTodoHistorial.getStyleClass().add("boton-pequeno");
        btnVerTodoHistorial.setOnAction(ev -> servicios.mainApp().navegarA("Historial"));
        barraHeaderOp.getChildren().addAll(lblTituloOp, btnVerTodoHistorial);

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

        TableColumn<OperationRecord, String> colOpVps = new TableColumn<>("Servidor");
        colOpVps.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getServidor()));

        TableColumn<OperationRecord, Node> colOpEstado = new TableColumn<>("Estado");
        colOpEstado.setCellValueFactory(c -> {
            String est = c.getValue().getEstado();
            String clase = "EXITOSA".equalsIgnoreCase(est) ? "exito" : ("FALLIDA".equalsIgnoreCase(est) ? "error" : "en-curso");
            return new SimpleObjectProperty<>(UiHelper.badge(est, clase));
        });

        TableColumn<OperationRecord, String> colOpInicio = new TableColumn<>("Fecha");
        colOpInicio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getInicioEn()));

        TableColumn<OperationRecord, String> colOpDetalle = new TableColumn<>("Detalle");
        colOpDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDetalle()));

        tablaOperaciones.getColumns().addAll(colOpId, colOpTipo, colOpInst, colOpVps, colOpEstado, colOpInicio, colOpDetalle);
        secOp.getChildren().addAll(barraHeaderOp, tablaOperaciones);

        // Agrupación en el contenedor principal
        contenedor.getChildren().addAll(cabecera, accionesRapidas, bannerSalud, metricas, panelAnaliticas, contenedorFlotaVps, secInst, secOp);
    }

    private HBox crearFilaMetricaOperacion(String etiqueta, ProgressBar pb, Label lblValor) {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);

        Label lblTag = new Label(etiqueta);
        lblTag.setPrefWidth(180);
        lblTag.setStyle("-fx-font-size: 11px; -fx-text-fill: #cbd5e1;");

        HBox.setHgrow(pb, Priority.ALWAYS);

        lblValor.setPrefWidth(40);
        lblValor.setAlignment(Pos.CENTER_RIGHT);
        lblValor.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

        box.getChildren().addAll(lblTag, pb, lblValor);
        return box;
    }

    private void abrirUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            log.error("No se pudo abrir el navegador para la URL {}: {}", url, e.getMessage());
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
            List<OperationRecord> listaOps = servicios.app().operaciones.listar(100);
            List<BackupRecord> listaBackups = servicios.app().respaldos.listarTodos();

            // Cálculos de Proyectos
            long prod = listaInst.stream().filter(i -> i.getEstado() == InstitutionStatus.INSTALADA).count();
            long curso = listaInst.stream().filter(i -> i.getEstado() == InstitutionStatus.INSTALANDO || i.getEstado() == InstitutionStatus.ACTUALIZANDO).count();
            long errores = listaInst.stream().filter(i -> i.getEstado() == InstitutionStatus.ERROR).count();
            long pendientes = listaInst.size() - (prod + curso + errores);

            // Cálculos de Operaciones
            long exitosas = listaOps.stream().filter(o -> "EXITOSA".equalsIgnoreCase(o.getEstado())).count();
            long fallidas = listaOps.stream().filter(o -> "FALLIDA".equalsIgnoreCase(o.getEstado())).count();
            long totalOpsConcluidas = exitosas + fallidas;
            double tasaExito = totalOpsConcluidas > 0 ? (exitosas * 100.0 / totalOpsConcluidas) : 100.0;

            // Desglose por tipo
            long cantDespliegues = listaOps.stream().filter(o -> o.getTipo() != null && o.getTipo().toUpperCase().contains("DESPLIEGUE")).count();
            long cantActualizaciones = listaOps.stream().filter(o -> o.getTipo() != null && o.getTipo().toUpperCase().contains("ACTUALIZAC")).count();
            long cantRespaldos = listaOps.stream().filter(o -> o.getTipo() != null && o.getTipo().toUpperCase().contains("RESPALDO")).count();
            long cantOtros = listaOps.size() - (cantDespliegues + cantActualizaciones + cantRespaldos);

            // Cálculos de Respaldos
            long totalBytesBackups = listaBackups.stream().mapToLong(BackupRecord::getTamanoBytes).sum();

            Platform.runLater(() -> {
                // Actualizar KPIs superiores
                actualizarMetrica(cardTotalInst, String.valueOf(listaInst.size()), prod + " en producción (" + (listaInst.isEmpty() ? 0 : Math.round(prod * 100.0 / listaInst.size())) + "%)");
                actualizarMetrica(cardTotalVps, String.valueOf(listaVps.size()), listaVps.size() == 1 ? "1 VPS conectado" : listaVps.size() + " VPS conectados");
                actualizarMetrica(cardTasaExito, String.format(Locale.US, "%.1f%%", tasaExito), exitosas + " exitosas / " + listaOps.size() + " tareas");
                actualizarMetrica(cardTotalBackups, listaBackups.size() + " copias", formatearBytes(totalBytesBackups) + " asegurados");

                // Actualizar Banner Inteligente de Salud
                boolean hayErrores = errores > 0 || (fallidas > 0 && !listaOps.isEmpty() && "FALLIDA".equalsIgnoreCase(listaOps.get(0).getEstado()));
                actualizarBannerSalud(hayErrores, errores, fallidas);

                // Actualizar Gráfico Pie de Distribución
                actualizarChartDistribucion(prod, curso, errores, pendientes);

                // Actualizar Pills de Resumen
                long totalI = listaInst.isEmpty() ? 1 : listaInst.size();
                lblPillProd.setText("● En Producción: " + prod + " (" + Math.round(prod * 100.0 / totalI) + "%)");
                lblPillCurso.setText("● En Despliegue: " + curso);
                lblPillError.setText("● Con Errores: " + errores);
                lblPillPend.setText("● Pendientes: " + pendientes);

                // Actualizar Métricas de Operaciones
                lblPorcentajeExito.setText(String.format(Locale.US, "%.1f%%", tasaExito));
                pbTasaExito.setProgress(tasaExito / 100.0);
                if (tasaExito >= 90.0) {
                    pbTasaExito.setStyle("-fx-accent: #10b981;");
                    lblPorcentajeExito.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #34d399;");
                    lblBadgeConfiabilidad.setText("ALTA CONFIABILIDAD");
                    lblBadgeConfiabilidad.getStyleClass().setAll("badge", "badge-exito");
                } else if (tasaExito >= 70.0) {
                    pbTasaExito.setStyle("-fx-accent: #f59e0b;");
                    lblPorcentajeExito.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #fbbf24;");
                    lblBadgeConfiabilidad.setText("ATENCIÓN");
                    lblBadgeConfiabilidad.getStyleClass().setAll("badge", "badge-en-curso");
                } else {
                    pbTasaExito.setStyle("-fx-accent: #ef4444;");
                    lblPorcentajeExito.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #f87171;");
                    lblBadgeConfiabilidad.setText("CRÍTICO");
                    lblBadgeConfiabilidad.getStyleClass().setAll("badge", "badge-error");
                }
                lblConteoOps.setText(exitosas + " exitosas de " + listaOps.size() + " operaciones registradas");

                // Actualizar Barras de Progreso por Tipo
                long maxTipo = Math.max(1, listaOps.size());
                pbOpDespliegues.setProgress((double) cantDespliegues / maxTipo);
                lblOpDespliegues.setText(String.valueOf(cantDespliegues));

                pbOpActualizaciones.setProgress((double) cantActualizaciones / maxTipo);
                lblOpActualizaciones.setText(String.valueOf(cantActualizaciones));

                pbOpRespaldos.setProgress((double) cantRespaldos / maxTipo);
                lblOpRespaldos.setText(String.valueOf(cantRespaldos));

                pbOpOtros.setProgress((double) Math.max(0, cantOtros) / maxTipo);
                lblOpOtros.setText(String.valueOf(Math.max(0, cantOtros)));

                // Actualizar Última Actividad
                if (!listaOps.isEmpty()) {
                    OperationRecord ult = listaOps.get(0);
                    lblUltimaActividad.setText(ult.getTipo() + " en " + (ult.getInstitucion().isBlank() ? "VPS" : ult.getInstitucion()) + " · " + ult.getInicioEn());
                    String est = ult.getEstado();
                    lblUltimaActividadEstado.setText(est);
                    lblUltimaActividadEstado.getStyleClass().setAll("badge",
                            "EXITOSA".equalsIgnoreCase(est) ? "badge-exito" : ("FALLIDA".equalsIgnoreCase(est) ? "badge-error" : "badge-en-curso"));
                } else {
                    lblUltimaActividad.setText("Sin operaciones registradas");
                    lblUltimaActividadEstado.setText("INACTIVO");
                    lblUltimaActividadEstado.getStyleClass().setAll("badge", "badge-pendiente");
                }

                // Actualizar Flota VPS
                actualizarFlotaVps(listaVps, listaInst);

                // Actualizar Tablas
                listaMasterInst.setAll(listaInst);
                tablaOperaciones.getItems().setAll(listaOps.subList(0, Math.min(15, listaOps.size())));
            });
        } catch (Exception e) {
            log.error("Error al refrescar dashboard con analíticas: {}", e.getMessage(), e);
        }
    }

    private void actualizarBannerSalud(boolean hayErrores, long cantErrores, long cantFallidas) {
        bannerSalud.getStyleClass().removeAll("banner-salud-ok", "banner-salud-alerta");
        bannerAcciones.getChildren().clear();

        if (hayErrores) {
            bannerSalud.getStyleClass().add("banner-salud-alerta");
            lblBannerIcono.setText("⚠️");
            lblBannerTitulo.setText("Atención: Incidencias o Fallos Recientes Detectados");
            lblBannerTitulo.getStyleClass().setAll("banner-titulo-alerta");
            lblBannerDesc.setText("Se detectaron " + cantErrores + " proyectos con incidencias o fallos en las operaciones recientes. Revisa el registro de auditoría.");

            Button btnVerAuditoria = new Button("Ver Historial de Fallos");
            btnVerAuditoria.getStyleClass().add("boton-peligro");
            btnVerAuditoria.setOnAction(ev -> servicios.mainApp().navegarA("Historial"));
            bannerAcciones.getChildren().addAll(btnVerAuditoria);
        } else {
            bannerSalud.getStyleClass().add("banner-salud-ok");
            lblBannerIcono.setText("🛡️");
            lblBannerTitulo.setText("Ecosistema 100% Operativo y Saludable");
            lblBannerTitulo.getStyleClass().setAll("banner-titulo-ok");
            lblBannerDesc.setText("Todas las aplicaciones en producción están activas y las tareas recientes concluyeron sin anomalías.");

            Label badgeOk = UiHelper.badge("SISTEMA ESTABLE", "exito");
            bannerAcciones.getChildren().add(badgeOk);
        }
    }

    private void actualizarChartDistribucion(long prod, long curso, long errores, long pendientes) {
        ObservableList<PieChart.Data> datos = FXCollections.observableArrayList();
        if (prod > 0) datos.add(new PieChart.Data("Producción (" + prod + ")", prod));
        if (curso > 0) datos.add(new PieChart.Data("En Proceso (" + curso + ")", curso));
        if (errores > 0) datos.add(new PieChart.Data("Con Error (" + errores + ")", errores));
        if (pendientes > 0) datos.add(new PieChart.Data("Pendientes (" + pendientes + ")", pendientes));

        if (datos.isEmpty()) {
            datos.add(new PieChart.Data("Sin proyectos", 1));
        }
        chartDistribucion.setData(datos);
    }

    private void actualizarFlotaVps(List<VpsServer> listaVps, List<Institution> listaInst) {
        // Mantener solo los primeros 2 elementos (título y subtítulo)
        while (contenedorFlotaVps.getChildren().size() > 2) {
            contenedorFlotaVps.getChildren().remove(2);
        }

        if (listaVps.isEmpty()) {
            Label vacio = new Label("No hay servidores VPS registrados aún. Haz clic en 'Servidores VPS' para añadir uno.");
            vacio.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
            contenedorFlotaVps.getChildren().add(vacio);
            return;
        }

        // Conteo de proyectos por VPS
        Map<Long, Long> conteoPorVps = new HashMap<>();
        for (Institution inst : listaInst) {
            conteoPorVps.put(inst.getVpsId(), conteoPorVps.getOrDefault(inst.getVpsId(), 0L) + 1);
        }

        HBox filaServidores = new HBox(12);
        for (VpsServer vps : listaVps) {
            long alojados = conteoPorVps.getOrDefault(vps.getId(), 0L);
            double proporcion = listaInst.isEmpty() ? 0.0 : (alojados * 1.0 / listaInst.size());

            VpsCardMini card = new VpsCardMini(vps, alojados, proporcion, () -> {
                servicios.mainApp().navegarA("Monitoreo");
            });
            HBox.setHgrow(card, Priority.ALWAYS);
            filaServidores.getChildren().add(card);
        }
        contenedorFlotaVps.getChildren().add(filaServidores);
    }

    private void actualizarMetrica(VBox card, String valor, String subtexto) {
        if (card.getChildren().size() > 1 && card.getChildren().get(1) instanceof Label lblValor) {
            lblValor.setText(valor);
        }
        if (card.getChildren().size() > 2 && card.getChildren().get(2) instanceof Label lblSub) {
            lblSub.setText(subtexto);
        }
    }

    private static String formatearBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] unidades = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= unidades.length) digitGroups = unidades.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), unidades[digitGroups]);
    }

    /** Mini componente para mostrar un servidor VPS con su carga de proyectos. */
    private static class VpsCardMini extends VBox {
        public VpsCardMini(VpsServer vps, long alojados, double proporcion, Runnable onMonitorear) {
            super(6);
            getStyleClass().add("card");
            setStyle("-fx-background-color: #12151c; -fx-padding: 10px 14px; -fx-border-color: #232938; -fx-border-radius: 6px;");

            HBox filaTit = new HBox(8);
            filaTit.setAlignment(Pos.CENTER_LEFT);

            Label lblNombre = new Label("🖥️ " + vps.getNombre());
            lblNombre.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #f8fafc;");
            HBox.setHgrow(lblNombre, Priority.ALWAYS);

            Label badgeConectado = UiHelper.badge("ACTIVO", "exito");
            filaTit.getChildren().addAll(lblNombre, badgeConectado);

            Label lblIp = new Label(vps.getHost() + ":" + vps.getPuerto() + " (" + vps.getUsuario() + ")");
            lblIp.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");

            HBox filaCarga = new HBox(8);
            filaCarga.setAlignment(Pos.CENTER_LEFT);
            ProgressBar pb = new ProgressBar(proporcion);
            pb.setStyle("-fx-accent: #10b981;");
            HBox.setHgrow(pb, Priority.ALWAYS);

            Label lblProy = new Label(alojados + (alojados == 1 ? " proyecto" : " proyectos"));
            lblProy.setStyle("-fx-font-size: 11px; -fx-text-fill: #cbd5e1; -fx-font-weight: bold;");
            filaCarga.getChildren().addAll(pb, lblProy);

            getChildren().addAll(filaTit, lblIp, filaCarga);
        }
    }
}
