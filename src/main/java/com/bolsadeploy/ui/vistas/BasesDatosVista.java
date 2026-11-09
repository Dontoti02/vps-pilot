package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.database.DatabaseInfo;
import com.bolsadeploy.database.DatabaseManagerService;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Vista de administración integral de bases de datos remotas en VPS
 * con soporte para múltiples motores: MySQL/MariaDB, PostgreSQL, SQLite y Redis.
 */
public class BasesDatosVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(BasesDatosVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    // Selector VPS y filtros
    private ComboBox<VpsServer> cbVps;
    private ComboBox<String> cbMotorFiltro;
    private Button btnDiagnosticar;
    private Button btnCrearDb;
    private Button btnRefrescar;

    // Tarjetas de estado de motores
    private HBox filaMotores;
    private VBox cardMysql;
    private VBox cardPgsql;
    private VBox cardSqlite;
    private VBox cardRedis;

    // Tabla de bases de datos
    private final ObservableList<DatabaseInfo> listaMasterDb = FXCollections.observableArrayList();
    private FilteredList<DatabaseInfo> listaFiltradaDb;
    private TableView<DatabaseInfo> tablaDatabases;
    private TextField txtBuscarDb;

    // Inspector derecho
    private Label lblInspectorTitulo;
    private Label lblInspectorSub;
    private TabPane tabPaneDetalle;

    // Pestaña 1: Tablas
    private TableView<DatabaseInfo.TableInfo> tablaTablas;

    // Pestaña 2: Consola SQL
    private TextArea txtConsultaSql;
    private Button btnEjecutarSql;
    private TableView<List<String>> tablaResultadosSql;
    private Label lblEstadoSql;

    // Pestaña 3: Mantenimiento
    private TextArea txtSalidaMantenimiento;

    private DatabaseInfo dbSeleccionadaActual;

    public BasesDatosVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(20));
        contenedor.setMaxWidth(1350);

        scroll = new ScrollPane(contenedor);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Gestión de Bases de Datos Multimotor",
                "Monitoreo, consultas, optimización y administración remota de bases de datos MySQL, PostgreSQL y SQLite en tus servidores VPS.");

        // ==========================================================
        // 1. Barra de Herramientas y Filtros
        // ==========================================================
        HBox barraTop = new HBox(12);
        barraTop.setAlignment(Pos.CENTER_LEFT);
        barraTop.setPadding(new Insets(4, 0, 8, 0));

        Label lblVps = new Label("Servidor VPS:");
        lblVps.setStyle("-fx-font-weight: bold; -fx-text-fill: #cbd5e1;");

        cbVps = new ComboBox<>();
        cbVps.setPrefWidth(220);
        cbVps.setPromptText("Seleccione un VPS...");
        cbVps.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(VpsServer v) {
                return v == null ? "" : v.getNombre() + " (" + v.getHost() + ")";
            }
            @Override
            public VpsServer fromString(String string) { return null; }
        });
        cbVps.setOnAction(ev -> cargarBasesDatos());

        Label lblMotor = new Label("Motor:");
        lblMotor.setStyle("-fx-font-weight: bold; -fx-text-fill: #cbd5e1;");

        cbMotorFiltro = new ComboBox<>(FXCollections.observableArrayList("TODOS", "MYSQL", "POSTGRESQL", "SQLITE"));
        cbMotorFiltro.setValue("TODOS");
        cbMotorFiltro.setOnAction(ev -> aplicarFiltros());

        btnDiagnosticar = new Button("🩺 Diagnosticar Motores");
        btnDiagnosticar.setOnAction(ev -> diagnosticarMotores());

        btnCrearDb = new Button("➕ Nueva Base de Datos");
        btnCrearDb.getStyleClass().add("boton-primario");
        btnCrearDb.setOnAction(ev -> abrirDialogoCrearDb());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> cargarBasesDatos());

        barraTop.getChildren().addAll(lblVps, cbVps, lblMotor, cbMotorFiltro, btnDiagnosticar, btnCrearDb, spacer, btnRefrescar);

        // ==========================================================
        // 2. Tarjetas de Diagnóstico de Motores de BD
        // ==========================================================
        filaMotores = new HBox(12);
        cardMysql = crearCardMotor("MySQL / MariaDB", "Verificando...", "Puerto 3306", "#3b82f6");
        cardPgsql = crearCardMotor("PostgreSQL", "Verificando...", "Puerto 5432", "#8b5cf6");
        cardSqlite = crearCardMotor("SQLite", "Verificando...", "Archivos locales", "#f59e0b");
        cardRedis = crearCardMotor("Redis", "Verificando...", "Puerto 6379", "#ef4444");
        filaMotores.getChildren().addAll(cardMysql, cardPgsql, cardSqlite, cardRedis);

        // ==========================================================
        // 3. Panel Principal Dividido (SplitPane)
        // ==========================================================
        SplitPane split = new SplitPane();
        split.setPrefHeight(550);
        split.setStyle("-fx-background-color: transparent; -fx-box-border: transparent;");

        // --- Panel Izquierdo: Lista de Bases de Datos ---
        VBox panelIzquierdo = new VBox(10);
        panelIzquierdo.setPadding(new Insets(10));
        panelIzquierdo.getStyleClass().add("card");

        HBox headIzquierdo = new HBox(8);
        headIzquierdo.setAlignment(Pos.CENTER_LEFT);
        Label lblTitDb = new Label("Bases de Datos Detectadas");
        lblTitDb.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #f8fafc;");
        HBox.setHgrow(lblTitDb, Priority.ALWAYS);

        txtBuscarDb = new TextField();
        txtBuscarDb.setPromptText("🔍 Buscar base...");
        txtBuscarDb.setPrefWidth(160);
        txtBuscarDb.getStyleClass().add("search-field");
        txtBuscarDb.textProperty().addListener((obs, o, n) -> aplicarFiltros());

        headIzquierdo.getChildren().addAll(lblTitDb, txtBuscarDb);

        tablaDatabases = new TableView<>();
        VBox.setVgrow(tablaDatabases, Priority.ALWAYS);
        tablaDatabases.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        listaFiltradaDb = new FilteredList<>(listaMasterDb, p -> true);
        tablaDatabases.setItems(listaFiltradaDb);

        TableColumn<DatabaseInfo, Node> colMotor = new TableColumn<>("Motor");
        colMotor.setCellValueFactory(c -> {
            String m = c.getValue().getMotor();
            String clase = switch (m.toUpperCase()) {
                case "MYSQL" -> "badge-exito";
                case "POSTGRESQL" -> "badge-en-curso";
                case "SQLITE" -> "badge-pendiente";
                default -> "badge-info";
            };
            return new SimpleObjectProperty<>(UiHelper.badge(m, clase));
        });
        colMotor.setMaxWidth(90);

        TableColumn<DatabaseInfo, String> colNombre = new TableColumn<>("Nombre / Archivo");
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));

        TableColumn<DatabaseInfo, String> colTablas = new TableColumn<>("Tablas");
        colTablas.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getTotalTablas())));
        colTablas.setMaxWidth(65);

        TableColumn<DatabaseInfo, String> colTamano = new TableColumn<>("Tamaño");
        colTamano.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTamano()));
        colTamano.setMaxWidth(85);

        TableColumn<DatabaseInfo, String> colProyecto = new TableColumn<>("Proyecto");
        colProyecto.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProyectoAsociado()));

        tablaDatabases.getColumns().addAll(colMotor, colNombre, colTablas, colTamano, colProyecto);

        tablaDatabases.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                seleccionarBaseDatos(newVal);
            }
        });

        // Botones de acciones sobre la BD seleccionada
        HBox accionesDb = new HBox(8);
        accionesDb.setAlignment(Pos.CENTER_LEFT);

        Button btnOptimizar = new Button("🧹 Optimizar");
        btnOptimizar.getStyleClass().add("boton-pequeno");
        btnOptimizar.setOnAction(ev -> optimizarBaseSeleccionada());

        Button btnExportar = new Button("💾 Exportar");
        btnExportar.getStyleClass().add("boton-pequeno");
        btnExportar.setOnAction(ev -> exportarDumpBaseSeleccionada());

        Button btnImportar = new Button("📥 Importar");
        btnImportar.getStyleClass().add("boton-pequeno");
        btnImportar.setOnAction(ev -> importarDumpBaseSeleccionada());

        Button btnEliminar = new Button("🗑 Eliminar");
        btnEliminar.getStyleClass().addAll("boton-pequeno", "boton-peligro");
        btnEliminar.setOnAction(ev -> eliminarBaseSeleccionada());

        accionesDb.getChildren().addAll(btnOptimizar, btnExportar, btnImportar, btnEliminar);

        panelIzquierdo.getChildren().addAll(headIzquierdo, tablaDatabases, accionesDb);

        // --- Panel Derecho: Inspector y Consola SQL ---
        VBox panelDerecho = new VBox(10);
        panelDerecho.setPadding(new Insets(10));
        panelDerecho.getStyleClass().add("card");

        VBox headInspector = new VBox(2);
        lblInspectorTitulo = new Label("Seleccione una base de datos");
        lblInspectorTitulo.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #f8fafc;");

        lblInspectorSub = new Label("Haga clic en una base de datos de la lista para inspeccionar tablas o ejecutar consultas.");
        lblInspectorSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        headInspector.getChildren().addAll(lblInspectorTitulo, lblInspectorSub);

        tabPaneDetalle = new TabPane();
        VBox.setVgrow(tabPaneDetalle, Priority.ALWAYS);

        // Tab 1: Tablas
        Tab tabTablas = new Tab("📋 Explorador de Tablas");
        tabTablas.setClosable(false);
        VBox boxTabTablas = new VBox(8);
        boxTabTablas.setPadding(new Insets(8));

        tablaTablas = new TableView<>();
        VBox.setVgrow(tablaTablas, Priority.ALWAYS);
        tablaTablas.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DatabaseInfo.TableInfo, String> colTNombre = new TableColumn<>("Tabla");
        colTNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));

        TableColumn<DatabaseInfo.TableInfo, String> colTFilas = new TableColumn<>("Filas Est.");
        colTFilas.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getFilas())));
        colTFilas.setMaxWidth(100);

        TableColumn<DatabaseInfo.TableInfo, String> colTTamano = new TableColumn<>("Tamaño");
        colTTamano.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTamano()));
        colTTamano.setMaxWidth(100);

        TableColumn<DatabaseInfo.TableInfo, String> colTMotor = new TableColumn<>("Tipo / Motor");
        colTMotor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getMotor()));
        colTMotor.setMaxWidth(120);

        tablaTablas.getColumns().addAll(colTNombre, colTFilas, colTTamano, colTMotor);

        HBox barraAccionTablas = new HBox(8);
        barraAccionTablas.setAlignment(Pos.CENTER_LEFT);
        Button btnVerFilas = new Button("🔍 Previsualizar Datos (SELECT * LIMIT 50)");
        btnVerFilas.setOnAction(ev -> previsualizarTablaSeleccionada());
        barraAccionTablas.getChildren().add(btnVerFilas);

        boxTabTablas.getChildren().addAll(tablaTablas, barraAccionTablas);
        tabTablas.setContent(boxTabTablas);

        // Tab 2: Consola SQL
        Tab tabSql = new Tab("⚡ Consola SQL");
        tabSql.setClosable(false);
        VBox boxTabSql = new VBox(8);
        boxTabSql.setPadding(new Insets(8));

        HBox barraAtajosSql = new HBox(8);
        barraAtajosSql.setAlignment(Pos.CENTER_LEFT);
        Label lblAtajos = new Label("Atajos:");
        lblAtajos.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");

        Button btnSqlTablas = new Button("SHOW TABLES");
        btnSqlTablas.getStyleClass().add("boton-pequeno");
        btnSqlTablas.setOnAction(ev -> insertarSql("SHOW TABLES;"));

        Button btnSqlSelect = new Button("SELECT Top 50");
        btnSqlSelect.getStyleClass().add("boton-pequeno");
        btnSqlSelect.setOnAction(ev -> {
            var sel = tablaTablas.getSelectionModel().getSelectedItem();
            String t = sel != null ? sel.getNombre() : "users";
            insertarSql("SELECT * FROM " + t + " LIMIT 50;");
        });

        Button btnSqlCount = new Button("COUNT Registros");
        btnSqlCount.getStyleClass().add("boton-pequeno");
        btnSqlCount.setOnAction(ev -> {
            var sel = tablaTablas.getSelectionModel().getSelectedItem();
            String t = sel != null ? sel.getNombre() : "users";
            insertarSql("SELECT COUNT(*) AS total FROM " + t + ";");
        });

        barraAtajosSql.getChildren().addAll(lblAtajos, btnSqlTablas, btnSqlSelect, btnSqlCount);

        txtConsultaSql = new TextArea("SELECT * FROM users LIMIT 10;");
        txtConsultaSql.setPrefHeight(90);
        txtConsultaSql.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");

        HBox filaEjecutar = new HBox(10);
        filaEjecutar.setAlignment(Pos.CENTER_LEFT);
        btnEjecutarSql = new Button("▶ Ejecutar Consulta");
        btnEjecutarSql.getStyleClass().add("boton-primario");
        btnEjecutarSql.setOnAction(ev -> ejecutarConsultaSql());

        lblEstadoSql = new Label("Escriba su consulta y presione 'Ejecutar'.");
        lblEstadoSql.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        HBox.setHgrow(lblEstadoSql, Priority.ALWAYS);
        filaEjecutar.getChildren().addAll(btnEjecutarSql, lblEstadoSql);

        tablaResultadosSql = new TableView<>();
        VBox.setVgrow(tablaResultadosSql, Priority.ALWAYS);

        boxTabSql.getChildren().addAll(barraAtajosSql, txtConsultaSql, filaEjecutar, tablaResultadosSql);
        tabSql.setContent(boxTabSql);

        // Tab 3: Mantenimiento y Logs
        Tab tabMant = new Tab("🛠 Diagnóstico y Salida Técnica");
        tabMant.setClosable(false);
        VBox boxTabMant = new VBox(8);
        boxTabMant.setPadding(new Insets(8));

        Label lblTitMant = new Label("Registro de operaciones de base de datos:");
        lblTitMant.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #cbd5e1;");

        txtSalidaMantenimiento = new TextArea();
        txtSalidaMantenimiento.setEditable(false);
        VBox.setVgrow(txtSalidaMantenimiento, Priority.ALWAYS);
        txtSalidaMantenimiento.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        boxTabMant.getChildren().addAll(lblTitMant, txtSalidaMantenimiento);
        tabMant.setContent(boxTabMant);

        tabPaneDetalle.getTabs().addAll(tabTablas, tabSql, tabMant);
        panelDerecho.getChildren().addAll(headInspector, tabPaneDetalle);

        split.getItems().addAll(panelIzquierdo, panelDerecho);
        split.setDividerPositions(0.42);

        contenedor.getChildren().addAll(cabecera, barraTop, filaMotores, split);
    }

    private VBox crearCardMotor(String titulo, String estado, String sub, String colorBorde) {
        VBox card = new VBox(4);
        card.getStyleClass().addAll("tarjeta-metrica", "card");
        card.setStyle("-fx-border-color: " + colorBorde + "; -fx-border-width: 0 0 0 4px;");
        HBox.setHgrow(card, Priority.ALWAYS);

        Label lblTit = new Label(titulo.toUpperCase());
        lblTit.getStyleClass().add("metrica-titulo");

        Label lblValor = new Label(estado);
        lblValor.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

        Label lblSub = new Label(sub);
        lblSub.getStyleClass().add("metrica-subtexto");

        card.getChildren().addAll(lblTit, lblValor, lblSub);
        return card;
    }

    private void actualizarCardMotor(VBox card, String valor, String sub, boolean activo) {
        if (card.getChildren().size() > 1 && card.getChildren().get(1) instanceof Label lblV) {
            lblV.setText(valor);
            lblV.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + (activo ? "#34d399;" : "#94a3b8;"));
        }
        if (card.getChildren().size() > 2 && card.getChildren().get(2) instanceof Label lblS) {
            lblS.setText(sub);
        }
    }

    @Override
    public Node raiz() {
        return scroll;
    }

    @Override
    public void alMostrar() {
        try {
            List<VpsServer> vpsList = servicios.app().vps.listar();
            cbVps.getItems().setAll(vpsList);
            if (!vpsList.isEmpty() && cbVps.getValue() == null) {
                cbVps.setValue(vpsList.get(0));
            }
            if (cbVps.getValue() != null) {
                cargarBasesDatos();
                diagnosticarMotores();
            }
        } catch (Exception e) {
            log.error("Error al mostrar vista de bases de datos: {}", e.getMessage(), e);
        }
    }

    private void diagnosticarMotores() {
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        btnDiagnosticar.setDisable(true);
        btnDiagnosticar.setText("Diagnosticando...");

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                List<DatabaseInfo.EngineStatus> status = svc.diagnosticarMotores(vps);

                Platform.runLater(() -> {
                    for (DatabaseInfo.EngineStatus s : status) {
                        String n = s.getNombre().toUpperCase();
                        if (n.contains("MYSQL")) {
                            actualizarCardMotor(cardMysql, s.isActivo() ? "ACTIVO" : "INACTIVO", s.getVersion(), s.isActivo());
                        } else if (n.contains("POSTGRES")) {
                            actualizarCardMotor(cardPgsql, s.isActivo() ? "ACTIVO" : "INACTIVO", s.getVersion(), s.isActivo());
                        } else if (n.contains("SQLITE")) {
                            actualizarCardMotor(cardSqlite, s.isActivo() ? "DISPONIBLE" : "NO DETECTADO", s.getVersion(), s.isActivo());
                        } else if (n.contains("REDIS")) {
                            actualizarCardMotor(cardRedis, s.isActivo() ? "ACTIVO" : "INACTIVO", s.getVersion(), s.isActivo());
                        }
                    }
                    btnDiagnosticar.setDisable(false);
                    btnDiagnosticar.setText("🩺 Diagnosticar Motores");
                });
            } catch (Exception e) {
                log.error("Error al diagnosticar motores: {}", e.getMessage());
                Platform.runLater(() -> {
                    btnDiagnosticar.setDisable(false);
                    btnDiagnosticar.setText("🩺 Diagnosticar Motores");
                });
            }
        }).start();
    }

    private void cargarBasesDatos() {
        VpsServer vps = cbVps.getValue();
        if (vps == null) {
            listaMasterDb.clear();
            return;
        }

        btnRefrescar.setDisable(true);

        new Thread(() -> {
            try {
                List<Institution> instList = servicios.app().instituciones.listar();
                DatabaseManagerService svc = servicios.app().databaseManager;
                List<DatabaseInfo> dbs = svc.listarBasesDatos(vps, instList);

                Platform.runLater(() -> {
                    listaMasterDb.setAll(dbs);
                    aplicarFiltros();
                    btnRefrescar.setDisable(false);
                    if (!listaFiltradaDb.isEmpty()) {
                        tablaDatabases.getSelectionModel().select(0);
                    }
                });
            } catch (Exception e) {
                log.error("Error al listar bases de datos: {}", e.getMessage());
                Platform.runLater(() -> {
                    btnRefrescar.setDisable(false);
                    UiHelper.alertaError("Error de Conexión", "No se pudieron listar las bases de datos: " + e.getMessage());
                });
            }
        }).start();
    }

    private void aplicarFiltros() {
        String motor = cbMotorFiltro.getValue();
        String buscar = txtBuscarDb.getText() != null ? txtBuscarDb.getText().toLowerCase().trim() : "";

        listaFiltradaDb.setPredicate(db -> {
            if (motor != null && !"TODOS".equalsIgnoreCase(motor) && !motor.equalsIgnoreCase(db.getMotor())) {
                return false;
            }
            if (!buscar.isEmpty()) {
                return db.getNombre().toLowerCase().contains(buscar)
                        || db.getMotor().toLowerCase().contains(buscar)
                        || db.getProyectoAsociado().toLowerCase().contains(buscar);
            }
            return true;
        });
    }

    private void seleccionarBaseDatos(DatabaseInfo db) {
        dbSeleccionadaActual = db;
        lblInspectorTitulo.setText("Base de datos: " + db.getNombre() + " (" + db.getMotor() + ")");
        lblInspectorSub.setText("Tamaño: " + db.getTamano() + " · Tablas: " + db.getTotalTablas() + " · Proyecto asociado: " + db.getProyectoAsociado());

        // Cargar tablas en background
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                List<DatabaseInfo.TableInfo> tablas = svc.listarTablas(vps, db);
                Platform.runLater(() -> tablaTablas.getItems().setAll(tablas));
            } catch (Exception e) {
                log.warn("Error al cargar tablas: {}", e.getMessage());
            }
        }).start();
    }

    private void insertarSql(String sql) {
        txtConsultaSql.setText(sql);
        tabPaneDetalle.getSelectionModel().select(1); // cambiar a tab SQL
    }

    private void previsualizarTablaSeleccionada() {
        DatabaseInfo.TableInfo t = tablaTablas.getSelectionModel().getSelectedItem();
        if (t == null) {
            UiHelper.alertaAdvertencia("Atención", "Selección requerida", "Seleccione una tabla para previsualizar sus datos.");
            return;
        }
        insertarSql("SELECT * FROM " + t.getNombre() + " LIMIT 50;");
        ejecutarConsultaSql();
    }

    private void ejecutarConsultaSql() {
        if (dbSeleccionadaActual == null) {
            UiHelper.alertaAdvertencia("Atención", "Base no seleccionada", "Seleccione una base de datos primero.");
            return;
        }
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        String sql = txtConsultaSql.getText();
        if (sql == null || sql.isBlank()) return;

        btnEjecutarSql.setDisable(true);
        lblEstadoSql.setText("Ejecutando consulta remota...");

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                DatabaseInfo.SqlQueryResult res = svc.ejecutarConsulta(vps, dbSeleccionadaActual, sql);

                Platform.runLater(() -> {
                    btnEjecutarSql.setDisable(false);
                    lblEstadoSql.setText(res.getMensaje());

                    tablaResultadosSql.getColumns().clear();
                    tablaResultadosSql.getItems().clear();

                    if (!res.isExito()) {
                        lblEstadoSql.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171; -fx-font-weight: bold;");
                        txtSalidaMantenimiento.appendText("\n[ERROR SQL] " + res.getMensaje() + "\n");
                        return;
                    }

                    lblEstadoSql.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399; -fx-font-weight: bold;");

                    // Crear columnas dinámicas
                    for (int i = 0; i < res.getColumnas().size(); i++) {
                        final int colIdx = i;
                        TableColumn<List<String>, String> col = new TableColumn<>(res.getColumnas().get(i));
                        col.setCellValueFactory(c -> {
                            List<String> fila = c.getValue();
                            return new SimpleStringProperty(colIdx < fila.size() ? fila.get(colIdx) : "");
                        });
                        tablaResultadosSql.getColumns().add(col);
                    }

                    tablaResultadosSql.getItems().addAll(res.getFilas());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnEjecutarSql.setDisable(false);
                    lblEstadoSql.setText("Error: " + e.getMessage());
                    lblEstadoSql.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");
                });
            }
        }).start();
    }

    private void optimizarBaseSeleccionada() {
        if (dbSeleccionadaActual == null) return;
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        if (!UiHelper.confirmar("Optimizar Base de Datos", "¿Desea ejecutar la optimización?",
                "Se compactará el espacio en disco y se reconstruirán los índices para " + dbSeleccionadaActual.getNombre())) {
            return;
        }

        tabPaneDetalle.getSelectionModel().select(2); // Salida técnica
        txtSalidaMantenimiento.appendText("\n>>> Iniciando optimización de " + dbSeleccionadaActual.getNombre() + " (" + dbSeleccionadaActual.getMotor() + ")...\n");

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                String res = svc.optimizarBaseDatos(vps, dbSeleccionadaActual);
                Platform.runLater(() -> {
                    txtSalidaMantenimiento.appendText(res + "\n>>> Optimización completada con éxito.\n");
                    UiHelper.alertaExito("Optimización Concluida", "La base de datos fue optimizada y desfragmentada correctamente.");
                    cargarBasesDatos();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    txtSalidaMantenimiento.appendText("\n[ERROR] " + e.getMessage() + "\n");
                    UiHelper.alertaError("Error de Optimización", e.getMessage());
                });
            }
        }).start();
    }

    private void exportarDumpBaseSeleccionada() {
        if (dbSeleccionadaActual == null) return;
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar Volcado SQL");
        fc.setInitialFileName(dbSeleccionadaActual.getMotor().toLowerCase() + "_" + dbSeleccionadaActual.getNombre() + ".sql.gz");
        File dest = fc.showSaveDialog(scroll.getScene().getWindow());
        if (dest == null) return;

        tabPaneDetalle.getSelectionModel().select(2);
        txtSalidaMantenimiento.appendText("\n>>> Exportando volcado de " + dbSeleccionadaActual.getNombre() + " a " + dest.getAbsolutePath() + "...\n");

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                Path p = svc.exportarDump(vps, dbSeleccionadaActual, dest.toPath().getParent());
                Platform.runLater(() -> {
                    txtSalidaMantenimiento.appendText(">>> Volcado generado y descargado exitosamente en: " + p + "\n");
                    UiHelper.alertaExito("Volcado Completado", "El archivo de respaldo fue guardado con éxito.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    txtSalidaMantenimiento.appendText("\n[ERROR EXPORTACIÓN] " + e.getMessage() + "\n");
                    UiHelper.alertaError("Error al Exportar", e.getMessage());
                });
            }
        }).start();
    }

    private void importarDumpBaseSeleccionada() {
        if (dbSeleccionadaActual == null) return;
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Seleccionar archivo SQL a importar");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Archivos SQL (*.sql, *.sql.gz)", "*.sql", "*.gz"),
                new FileChooser.ExtensionFilter("Todos los archivos", "*.*")
        );
        File sel = fc.showOpenDialog(scroll.getScene().getWindow());
        if (sel == null) return;

        if (!UiHelper.confirmar("Confirmar Importación", "¿Sobrescribir datos en " + dbSeleccionadaActual.getNombre() + "?",
                "Se subirán y ejecutarán las instrucciones del archivo " + sel.getName() + " en la base de datos remota.")) {
            return;
        }

        tabPaneDetalle.getSelectionModel().select(2);
        txtSalidaMantenimiento.appendText("\n>>> Subiendo e importando " + sel.getName() + " en " + dbSeleccionadaActual.getNombre() + "...\n");

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                svc.importarDump(vps, dbSeleccionadaActual, sel.toPath());
                Platform.runLater(() -> {
                    txtSalidaMantenimiento.appendText(">>> Importación finalizada con éxito.\n");
                    UiHelper.alertaExito("Importación Exitosa", "Los datos fueron importados correctamente en " + dbSeleccionadaActual.getNombre());
                    cargarBasesDatos();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    txtSalidaMantenimiento.appendText("\n[ERROR IMPORTACIÓN] " + e.getMessage() + "\n");
                    UiHelper.alertaError("Error al Importar", e.getMessage());
                });
            }
        }).start();
    }

    private void eliminarBaseSeleccionada() {
        if (dbSeleccionadaActual == null) return;
        VpsServer vps = cbVps.getValue();
        if (vps == null) return;

        if (!UiHelper.confirmar("Eliminar Base de Datos", "ACCION DESTRUCCIÓN IRREVERSIBLE",
                "¿Está completamente seguro de eliminar la base de datos '" + dbSeleccionadaActual.getNombre() + "' (" + dbSeleccionadaActual.getMotor() + ")? Se perderán todas sus tablas y datos.")) {
            return;
        }

        new Thread(() -> {
            try {
                DatabaseManagerService svc = servicios.app().databaseManager;
                svc.eliminarBaseDatos(vps, dbSeleccionadaActual);
                Platform.runLater(() -> {
                    UiHelper.alertaExito("Base de Datos Eliminada", "La base de datos fue removida del servidor.");
                    cargarBasesDatos();
                });
            } catch (Exception e) {
                Platform.runLater(() -> UiHelper.alertaError("Error al Eliminar", e.getMessage()));
            }
        }).start();
    }

    private void abrirDialogoCrearDb() {
        VpsServer vps = cbVps.getValue();
        if (vps == null) {
            UiHelper.alertaAdvertencia("Atención", "Sin servidor", "Seleccione un servidor VPS antes de crear una base de datos.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Crear Nueva Base de Datos");
        dialog.setHeaderText("Crear base de datos y usuario en el VPS " + vps.getNombre());
        dialog.getDialogPane().getStyleClass().add("dialogo-moderno");

        VBox form = new VBox(10);
        form.setPadding(new Insets(10));

        ComboBox<String> cbMotor = new ComboBox<>(FXCollections.observableArrayList("MYSQL", "POSTGRESQL"));
        cbMotor.setValue("MYSQL");

        TextField txtNombre = new TextField();
        txtNombre.setPromptText("nombre_bd");

        TextField txtUsuario = new TextField();
        txtUsuario.setPromptText("usuario_bd");

        PasswordField txtPass = new PasswordField();
        txtPass.setPromptText("Contraseña");

        form.getChildren().addAll(
                UiHelper.campoFormulario("Motor de Base de Datos:", cbMotor, "Seleccione el motor relacional"),
                UiHelper.campoFormulario("Nombre de la Base de Datos:", txtNombre, "Solo caracteres alfanuméricos y guiones bajos"),
                UiHelper.campoFormulario("Usuario Dedicado:", txtUsuario, "Usuario con permisos completos sobre la BD"),
                UiHelper.campoFormulario("Contraseña:", txtPass, "Contraseña segura")
        );

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                String motor = cbMotor.getValue();
                String dbName = txtNombre.getText() != null ? txtNombre.getText().trim() : "";
                String user = txtUsuario.getText() != null ? txtUsuario.getText().trim() : "";
                String pass = txtPass.getText() != null ? txtPass.getText() : "";

                if (dbName.isEmpty()) {
                    UiHelper.alertaError("Error", "El nombre de la base de datos es obligatorio.");
                    return;
                }

                new Thread(() -> {
                    try {
                        DatabaseManagerService svc = servicios.app().databaseManager;
                        svc.crearBaseDatos(vps, motor, dbName, user, pass);
                        Platform.runLater(() -> {
                            UiHelper.alertaExito("Base Creada", "La base de datos '" + dbName + "' y el usuario '" + user + "' fueron creados correctamente.");
                            cargarBasesDatos();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> UiHelper.alertaError("Error al Crear", e.getMessage()));
                    }
                }).start();
            }
        });
    }
}
