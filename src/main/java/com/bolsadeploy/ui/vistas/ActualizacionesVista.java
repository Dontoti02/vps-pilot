package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.UpdatePlan;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.ConsolaOperaciones;
import com.bolsadeploy.ui.componentes.UiHelper;
import com.bolsadeploy.updates.MassUpdateRunner;
import com.bolsadeploy.updates.UpdateExecutor;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Gestión de actualizaciones controladas: comparación de versiones en GitHub,
 * detección de migraciones de base de datos, aprobación de riesgos y actualización atómica.
 */
public class ActualizacionesVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(ActualizacionesVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    private ComboBox<Institution> cbInstitucion;
    private Button btnConsultarCambios;
    private Button btnEjecutarActualizacion;
    private CheckBox chkAprobarRiesgo;

    private VBox panelDetallePlan;
    private Label lblCommitActual;
    private Label lblCommitObjetivo;
    private Label lblRiesgo;
    private ListView<String> listaMigraciones;
    private ListView<String> listaArchivosSensibles;
    private TextArea txtResumenCambios;

    private ConsolaOperaciones consola;
    private UpdatePlan planActual;

    public ActualizacionesVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1100);

        scroll = new ScrollPane(contenedor);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Control de Actualizaciones",
                "Comprueba nuevos commits en GitHub, analiza migraciones pendientes y despliega sin interrumpir el servicio.");

        // Selector y botones de acción
        HBox barraSuperior = new HBox(12);
        barraSuperior.setAlignment(Pos.CENTER_LEFT);
        barraSuperior.getStyleClass().add("card");
        barraSuperior.setPadding(new Insets(14));

        Label lblInst = new Label("Institución:");
        lblInst.setStyle("-fx-font-weight: bold;");

        cbInstitucion = new ComboBox<>();
        cbInstitucion.setPrefWidth(300);
        cbInstitucion.setPromptText("Selecciona una institución...");
        cbInstitucion.setOnAction(ev -> limpiarPlan());

        btnConsultarCambios = new Button("🔍 Consultar Cambios en GitHub");
        btnConsultarCambios.getStyleClass().add("boton-primario");
        btnConsultarCambios.setOnAction(ev -> consultarCambios());

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        barraSuperior.getChildren().addAll(lblInst, cbInstitucion, btnConsultarCambios, btnRefrescar);

        // Panel de Plan de Actualización
        panelDetallePlan = new VBox(12);
        panelDetallePlan.getStyleClass().add("card");
        panelDetallePlan.setPadding(new Insets(16));
        panelDetallePlan.setVisible(false);
        panelDetallePlan.setManaged(false);

        Label lblPlanTitulo = new Label("Análisis de Cambios Detectados:");
        lblPlanTitulo.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(8);

        lblCommitActual = new Label("-");
        lblCommitObjetivo = new Label("-");
        lblRiesgo = new Label("-");

        grid.add(new Label("Commit Actual:"), 0, 0);
        grid.add(lblCommitActual, 1, 0);
        grid.add(new Label("Commit Objetivo (Remoto):"), 0, 1);
        grid.add(lblCommitObjetivo, 1, 1);
        grid.add(new Label("Nivel de Riesgo:"), 0, 2);
        grid.add(lblRiesgo, 1, 2);

        Label lblMigraciones = new Label("Migraciones de Base de Datos Detectadas:");
        listaMigraciones = new ListView<>();
        listaMigraciones.setPrefHeight(90);

        Label lblArchivos = new Label("Archivos Sensibles Modificados (.env, seeders, etc.):");
        listaArchivosSensibles = new ListView<>();
        listaArchivosSensibles.setPrefHeight(90);

        Label lblResumen = new Label("Mensajes de Commits:");
        txtResumenCambios = new TextArea();
        txtResumenCambios.setEditable(false);
        txtResumenCambios.setPrefRowCount(4);

        chkAprobarRiesgo = new CheckBox("He revisado los cambios de base de datos y autorizo la actualización");
        chkAprobarRiesgo.setStyle("-fx-font-weight: bold; -fx-text-fill: #ef4444;");

        btnEjecutarActualizacion = new Button("🚀 Aplicar Actualización Controlada");
        btnEjecutarActualizacion.getStyleClass().add("boton-primario");
        btnEjecutarActualizacion.setOnAction(ev -> ejecutarActualizacion());

        panelDetallePlan.getChildren().addAll(lblPlanTitulo, grid,
                lblMigraciones, listaMigraciones,
                lblArchivos, listaArchivosSensibles,
                lblResumen, txtResumenCambios,
                chkAprobarRiesgo, btnEjecutarActualizacion);

        // Consola de Operaciones
        consola = new ConsolaOperaciones();

        // Sección de Actualización Masiva
        VBox secMasiva = new VBox(10);
        secMasiva.getStyleClass().add("card");
        secMasiva.setPadding(new Insets(16));

        Label lblMasivaTitulo = new Label("Actualización Masiva de Instituciones");
        lblMasivaTitulo.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label lblMasivaDesc = new Label("Actualiza todas las instituciones cuyas ramas no presenten riesgos de migraciones destructivas.");
        lblMasivaDesc.setWrapText(true);

        Button btnActualizarMasivo = new Button("⚡ Actualizar Todas las Aptas en Paralelo");
        btnActualizarMasivo.setOnAction(ev -> ejecutarActualizacionMasiva());

        secMasiva.getChildren().addAll(lblMasivaTitulo, lblMasivaDesc, btnActualizarMasivo);

        contenedor.getChildren().addAll(cabecera, barraSuperior, panelDetallePlan, consola, secMasiva);
    }

    private void limpiarPlan() {
        planActual = null;
        panelDetallePlan.setVisible(false);
        panelDetallePlan.setManaged(false);
    }

    private void consultarCambios() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) {
            UiHelper.alertaAdvertencia("Selección Requerida", null, "Selecciona una institución para verificar actualizaciones.");
            return;
        }

        btnConsultarCambios.setDisable(true);
        consola.limpiar();
        consola.log("Consultando repositorio GitHub y analizando diferencias para " + inst.getNombre() + "...");

        new Thread(() -> {
            try {
                UpdatePlan plan = servicios.mantenimiento().planearActualizacion(inst);
                planActual = plan;

                Platform.runLater(() -> {
                    btnConsultarCambios.setDisable(false);
                    lblCommitActual.setText(plan.commitActual() == null || plan.commitActual().isBlank() ? "Desconocido / Inicial" : plan.commitActual());
                    lblCommitObjetivo.setText(plan.commitObjetivo());

                    if (plan.riesgoDestructivo()) {
                        lblRiesgo.setText("ALTO RIESGO (Contiene migraciones o cambios de esquema)");
                        lblRiesgo.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
                        chkAprobarRiesgo.setVisible(true);
                        chkAprobarRiesgo.setManaged(true);
                        chkAprobarRiesgo.setSelected(false);
                    } else {
                        lblRiesgo.setText("BAJO / SEGURO (Sin cambios destructivos en base de datos)");
                        lblRiesgo.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
                        chkAprobarRiesgo.setVisible(false);
                        chkAprobarRiesgo.setManaged(false);
                        chkAprobarRiesgo.setSelected(true);
                    }

                    listaMigraciones.getItems().setAll(plan.migracionesPendientes().isEmpty() ? List.of("Ninguna migración pendiente") : plan.migracionesPendientes());
                    listaArchivosSensibles.getItems().setAll(plan.archivosSensibles().isEmpty() ? List.of("Ningún archivo sensible comprometido") : plan.archivosSensibles());
                    txtResumenCambios.setText(plan.resumenCambios());

                    panelDetallePlan.setVisible(true);
                    panelDetallePlan.setManaged(true);

                    consola.log("Consulta de cambios completada con éxito. Revisa el plan.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnConsultarCambios.setDisable(false);
                    consola.log("[ERROR] " + e.getMessage());
                    UiHelper.alertaError("Error al Consultar GitHub", e.getMessage());
                });
            }
        }, "github-update-plan").start();
    }

    private void ejecutarActualizacion() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null || planActual == null) return;

        if (planActual.requiereAprobacionExplicita() && !chkAprobarRiesgo.isSelected()) {
            UiHelper.alertaAdvertencia("Aprobación Requerida", null,
                    "Esta actualización involucra cambios de base de datos. Debes marcar la casilla de confirmación para proceder.");
            return;
        }

        btnEjecutarActualizacion.setDisable(true);
        consola.limpiar();
        consola.log("Iniciando actualización atómica para " + inst.getNombre() + " hacia " + planActual.commitObjetivo() + "...");

        new Thread(() -> {
            try {
                UpdateExecutor.Resultado res = servicios.mantenimiento().actualizar(
                        inst,
                        planActual,
                        chkAprobarRiesgo.isSelected(),
                        servicios.prompt(),
                        consola.asDeploymentListener()
                );

                Platform.runLater(() -> {
                    btnEjecutarActualizacion.setDisable(false);
                    alMostrar();
                    if (res.exito()) {
                        consola.log("¡ACTUALIZACIÓN EXITOSA! Commit nuevo: " + res.commitNuevo());
                        UiHelper.alertaExito("Actualización Exitosa", "La institución '" + inst.getNombre() + "' fue actualizada a la versión " + res.commitNuevo());
                    } else {
                        consola.log("[FALLIDA] " + res.mensaje());
                        if (res.rollbackRealizado()) {
                            consola.log("[ROLLBACK] Se realizó con éxito la restauración a la versión previa: " + res.commitAnterior());
                        }
                        UiHelper.alertaError("Fallo en la Actualización", res.mensaje());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnEjecutarActualizacion.setDisable(false);
                    consola.log("[EXCEPCION] " + e.getMessage());
                    UiHelper.alertaError("Error en Actualización", e.getMessage());
                });
            }
        }, "actualizacion-exec").start();
    }

    private void ejecutarActualizacionMasiva() {
        try {
            List<Institution> lista = servicios.app().instituciones.listar();
            if (lista.isEmpty()) {
                UiHelper.alertaAdvertencia("Sin Instituciones", null, "No hay instituciones registradas.");
                return;
            }

            boolean confirma = UiHelper.confirmar("Actualización Masiva",
                    "¿Deseas iniciar la actualización masiva de todas las instituciones aptas?",
                    "Se verificarán cambios de forma concurrente y solo se aplicarán aquellas que no requieran aprobación de riesgo manual.");
            if (!confirma) return;

            consola.limpiar();
            consola.log("=== INICIANDO ACTUALIZACIÓN MASIVA ===");

            new Thread(() -> {
                try {
                    List<MassUpdateRunner.Resultado> res = servicios.mantenimiento().actualizarMasivo(
                            lista,
                            servicios.prompt(),
                            consola::log
                    );
                    Platform.runLater(() -> {
                        alMostrar();
                        long ok = res.stream().filter(MassUpdateRunner.Resultado::exito).count();
                        consola.log(String.format("=== FINALIZADO: %d exitosas de %d evaluadas ===", ok, res.size()));
                        UiHelper.alertaExito("Actualización Masiva Completada",
                                String.format("Se procesaron las instituciones.\nExitosas: %d\nFallidas/Omitidas: %d", ok, res.size() - ok));
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        consola.log("[ERROR MASIVO] " + e.getMessage());
                        UiHelper.alertaError("Error en Actualización Masiva", e.getMessage());
                    });
                }
            }, "mass-update-runner").start();

        } catch (Exception e) {
            UiHelper.alertaError("Error", e.getMessage());
        }
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
            log.error("Error al refrescar instituciones en actualizaciones: {}", e.getMessage(), e);
        }
    }
}

