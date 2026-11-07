package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.backups.BackupService;
import com.bolsadeploy.domain.BackupRecord;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.ConsolaOperaciones;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Gestión de copias de seguridad (BD, Archivos, Configuración e Integral),
 * descarga por SFTP a la computadora local y restauración con verificación.
 */
public class CopiasVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(CopiasVista.class);

    private final Servicios servicios;
    private final ScrollPane scroll;
    private final VBox contenedor;

    private ComboBox<Institution> cbInstitucion;
    private TableView<BackupRecord> tablaRespaldos;
    private ConsolaOperaciones consola;

    public CopiasVista(Servicios servicios) {
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
        VBox cabecera = UiHelper.cabeceraSeccion("Copias de Seguridad y Restauración",
                "Crea respaldos puntuales, descárgalos localmente y restaura en caso de contingencia.");

        // Selector y creación
        HBox barraSuperior = new HBox(12);
        barraSuperior.setAlignment(Pos.CENTER_LEFT);
        barraSuperior.getStyleClass().add("card");
        barraSuperior.setPadding(new Insets(14));

        Label lblInst = new Label("Institución:");
        lblInst.setStyle("-fx-font-weight: bold;");

        cbInstitucion = new ComboBox<>();
        cbInstitucion.setPrefWidth(260);
        cbInstitucion.setPromptText("Selecciona una institución...");
        cbInstitucion.setOnAction(ev -> cargarRespaldos());

        Button btnBd = new Button("+ Respaldo BD");
        btnBd.setOnAction(ev -> crearRespaldo(BackupService.Tipo.BD));

        Button btnArchivos = new Button("+ Respaldo Archivos");
        btnArchivos.setOnAction(ev -> crearRespaldo(BackupService.Tipo.ARCHIVOS));

        Button btnConfig = new Button("+ Respaldo Config");
        btnConfig.setOnAction(ev -> crearRespaldo(BackupService.Tipo.CONFIG));

        Button btnIntegral = new Button("+ Respaldo Integral");
        btnIntegral.getStyleClass().add("boton-primario");
        btnIntegral.setOnAction(ev -> crearRespaldo(BackupService.Tipo.INTEGRAL));

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> cargarRespaldos());

        barraSuperior.getChildren().addAll(lblInst, cbInstitucion, btnBd, btnArchivos, btnConfig, btnIntegral, btnRefrescar);

        // Tabla de respaldos
        VBox secTabla = new VBox(8);
        Label lblTablaTitulo = new Label("Respaldos Disponibles:");
        lblTablaTitulo.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox barraAccionesTabla = new HBox(10);
        barraAccionesTabla.setAlignment(Pos.CENTER_LEFT);

        Button btnDescargar = new Button("⬇ Descargar a mi PC (SFTP)");
        btnDescargar.setOnAction(ev -> descargarRespaldoSeleccionado());

        Button btnRestaurar = new Button("🔄 Restaurar en Servidor");
        btnRestaurar.getStyleClass().add("boton-peligro");
        btnRestaurar.setOnAction(ev -> restaurarRespaldoSeleccionado());

        Button btnPurgar = new Button("🗑️ Purgar Antiguos (Conservar 5)");
        btnPurgar.setOnAction(ev -> purgarRespaldos());

        barraAccionesTabla.getChildren().addAll(btnDescargar, btnRestaurar, btnPurgar);

        tablaRespaldos = new TableView<>();
        tablaRespaldos.setPrefHeight(260);
        tablaRespaldos.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<BackupRecord, String> colTipo = new TableColumn<>("Tipo");
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipo()));
        colTipo.setMaxWidth(100);

        TableColumn<BackupRecord, String> colId = new TableColumn<>("Identificador / Archivo");
        colId.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getIdentificador()));

        TableColumn<BackupRecord, String> colTamano = new TableColumn<>("Tamaño");
        colTamano.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTamano()));
        colTamano.setMaxWidth(110);

        TableColumn<BackupRecord, String> colUbicacion = new TableColumn<>("Ubicación en Servidor");
        colUbicacion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUbicacion()));

        TableColumn<BackupRecord, String> colFecha = new TableColumn<>("Fecha");
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCreadoEn()));
        colFecha.setMaxWidth(150);

        tablaRespaldos.getColumns().addAll(colTipo, colId, colTamano, colUbicacion, colFecha);
        secTabla.getChildren().addAll(lblTablaTitulo, barraAccionesTabla, tablaRespaldos);

        // Consola de Operaciones
        consola = new ConsolaOperaciones();

        contenedor.getChildren().addAll(cabecera, barraSuperior, secTabla, consola);
    }

    private void crearRespaldo(BackupService.Tipo tipo) {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) {
            UiHelper.alertaAdvertencia("Selección Requerida", null, "Selecciona una institución para generar el respaldo.");
            return;
        }

        consola.limpiar();
        consola.log("Iniciando creación de respaldo " + tipo + " para " + inst.getNombre() + "...");

        new Thread(() -> {
            try {
                BackupRecord b = servicios.mantenimiento().crearRespaldo(
                        inst,
                        tipo,
                        inst.getCommitDesplegado(),
                        servicios.prompt(),
                        consola.asDeploymentListener()
                );
                Platform.runLater(() -> {
                    cargarRespaldos();
                    consola.log("¡RESPALDO GENERADO CON ÉXITO! Archivo: " + b.getIdentificador() + " (" + b.getTamano() + ")");
                    UiHelper.alertaExito("Respaldo Creado", "Se generó el respaldo correctamente: " + b.getIdentificador());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    consola.log("[ERROR] " + e.getMessage());
                    UiHelper.alertaError("Error al Crear Respaldo", e.getMessage());
                });
            }
        }, "backup-create").start();
    }

    private void descargarRespaldoSeleccionado() {
        Institution inst = cbInstitucion.getValue();
        BackupRecord b = tablaRespaldos.getSelectionModel().getSelectedItem();
        if (inst == null || b == null) {
            UiHelper.alertaAdvertencia("Selección Requerida", null, "Selecciona un respaldo de la tabla.");
            return;
        }

        consola.limpiar();
        consola.log("Descargando por SFTP " + b.getIdentificador() + " a la carpeta local...");

        new Thread(() -> {
            try {
                Path local = servicios.mantenimiento().descargarRespaldo(
                        inst,
                        b.getUbicacion() != null ? b.getUbicacion() : b.getIdentificador(),
                        servicios.prompt(),
                        consola.asDeploymentListener()
                );
                Platform.runLater(() -> {
                    consola.log("¡Descarga completada con éxito en: " + local.toAbsolutePath());
                    UiHelper.alertaExito("Descarga Completada", "El archivo se descargó en:\n" + local.toAbsolutePath());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    consola.log("[ERROR DESCARGA] " + e.getMessage());
                    UiHelper.alertaError("Error de Descarga", e.getMessage());
                });
            }
        }, "sftp-download").start();
    }

    private void restaurarRespaldoSeleccionado() {
        Institution inst = cbInstitucion.getValue();
        BackupRecord b = tablaRespaldos.getSelectionModel().getSelectedItem();
        if (inst == null || b == null) {
            UiHelper.alertaAdvertencia("Selección Requerida", null, "Selecciona el respaldo a restaurar.");
            return;
        }

        // Diálogo de confirmación de 2 pasos
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Confirmación Crítica de Restauración");
        dialog.setHeaderText("¡ADVERTENCIA DE SOBREESCRITURA DE DATOS!");
        dialog.getDialogPane().getStyleClass().add("dialogo-moderno");

        VBox content = new VBox(12);
        Label lblWarn = new Label("""
                Vas a restaurar el respaldo '%s' de tipo [%s] en la institución '%s'.
                
                Esta operación reemplazará los datos o archivos actualmente presentes en el servidor.
                """.formatted(b.getIdentificador(), b.getTipo(), inst.getNombre()));
        lblWarn.setWrapText(true);

        CheckBox chkConfirma = new CheckBox("Entiendo que esta acción sobrescribirá los datos del servidor");
        chkConfirma.setStyle("-fx-font-weight: bold; -fx-text-fill: #ef4444;");

        content.getChildren().addAll(lblWarn, chkConfirma);
        dialog.getDialogPane().setContent(content);

        ButtonType btnRestaurar = new ButtonType("Restaurar Datos", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancelar = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(btnRestaurar, btnCancelar);

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == btnRestaurar) {
            if (!chkConfirma.isSelected()) {
                UiHelper.alertaAdvertencia("No Confirmado", null, "Debes marcar la casilla para confirmar la restauración.");
                return;
            }

            consola.limpiar();
            consola.log("Iniciando restauración de " + b.getIdentificador() + "...");

            BackupService.Tipo tipo = BackupService.Tipo.valueOf(b.getTipo());

            new Thread(() -> {
                try {
                    String detalle = servicios.mantenimiento().restaurarRespaldo(
                            inst,
                            tipo,
                            b.getUbicacion() != null ? b.getUbicacion() : b.getIdentificador(),
                            true,
                            servicios.prompt(),
                            consola.asDeploymentListener()
                    );
                    Platform.runLater(() -> {
                        consola.log("¡RESTAURACIÓN FINALIZADA! " + detalle);
                        UiHelper.alertaExito("Restauración Completada", detalle);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        consola.log("[ERROR RESTAURACIÓN] " + e.getMessage());
                        UiHelper.alertaError("Error al Restaurar", e.getMessage());
                    });
                }
            }, "restore-runner").start();
        }
    }

    private void purgarRespaldos() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) return;

        boolean conf = UiHelper.confirmar("Purgar Respaldos", "¿Conservar únicamente los 5 respaldos más recientes?",
                "Los respaldos más antiguos en el VPS serán eliminados para liberar espacio en disco.");
        if (!conf) return;

        new Thread(() -> {
            try {
                int eliminados = servicios.mantenimiento().purgarRespaldos(inst, 5, servicios.prompt(), consola.asDeploymentListener());
                Platform.runLater(() -> {
                    cargarRespaldos();
                    UiHelper.alertaExito("Purga Completada", "Se eliminaron " + eliminados + " respaldos antiguos.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> UiHelper.alertaError("Error al Purgar", e.getMessage()));
            }
        }, "purge-backups").start();
    }

    private void cargarRespaldos() {
        Institution inst = cbInstitucion.getValue();
        if (inst == null) {
            tablaRespaldos.getItems().clear();
            return;
        }

        new Thread(() -> {
            try {
                List<BackupRecord> lista = servicios.mantenimiento().listarRespaldos(inst, servicios.prompt(), null);
                Platform.runLater(() -> tablaRespaldos.getItems().setAll(lista));
            } catch (Exception e) {
                // si falla en el VPS, cargar al menos los locales registrados en SQLite
                try {
                    List<BackupRecord> listaLocal = servicios.app().respaldos.listarPorInstitucion(inst.getId());
                    Platform.runLater(() -> tablaRespaldos.getItems().setAll(listaLocal));
                } catch (Exception ignored) {
                }
            }
        }, "list-backups").start();
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
            log.error("Error al refrescar instituciones en respaldos: {}", e.getMessage(), e);
        }
    }
}
