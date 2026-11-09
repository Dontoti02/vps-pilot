package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.InstitutionStatus;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.security.CommandValidator;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
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
import java.util.Optional;

/**
 * Gestión de instituciones educativas y sus configuraciones de despliegue.
 */
public class InstitucionesVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(InstitucionesVista.class);

    private final Servicios servicios;
    private final VBox contenedor;
    private TableView<Institution> tabla;

    private final Map<Long, String> vpsNombres = new HashMap<>();

    public InstitucionesVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1300);

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Instituciones Educativas",
                "Gestiona las instituciones que cuentan o contarán con su propia instancia de bolsa laboral.");

        HBox barraAcciones = new HBox(10);
        barraAcciones.setAlignment(Pos.CENTER_LEFT);

        Button btnNuevo = new Button("+ Registrar Institución");
        btnNuevo.getStyleClass().add("boton-primario");
        btnNuevo.setOnAction(ev -> abrirDialogo(null));

        Button btnEditar = new Button("Editar");
        btnEditar.setOnAction(ev -> {
            Institution inst = tabla.getSelectionModel().getSelectedItem();
            if (inst != null) abrirDialogo(inst);
            else UiHelper.alertaAdvertencia("Selección requerida", null, "Selecciona una institución de la tabla.");
        });

        Button btnEliminar = new Button("Eliminar");
        btnEliminar.getStyleClass().add("boton-peligro");
        btnEliminar.setOnAction(ev -> eliminarInstitucion());

        Button btnAbrirWeb = new Button("🌐 Abrir Web");
        btnAbrirWeb.setOnAction(ev -> {
            Institution inst = tabla.getSelectionModel().getSelectedItem();
            if (inst != null) abrirWeb(inst.urlBase());
        });

        Button btnDesplegar = new Button("🚀 Ir a Despliegue");
        btnDesplegar.setOnAction(ev -> {
            Institution inst = tabla.getSelectionModel().getSelectedItem();
            if (inst != null) {
                servicios.mainApp().seleccionarDespliegue(inst);
            } else {
                servicios.mainApp().navegarA("Nuevo despliegue");
            }
        });

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        barraAcciones.getChildren().addAll(btnNuevo, btnEditar, btnEliminar, btnAbrirWeb, btnDesplegar, btnRefrescar);

        tabla = new TableView<>();
        tabla.setPrefHeight(450);
        tabla.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tabla, Priority.ALWAYS);

        TableColumn<Institution, String> colId = new TableColumn<>("ID");
        colId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getId())));
        colId.setMaxWidth(50);

        TableColumn<Institution, String> colNombre = new TableColumn<>("Nombre de Institución");
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));

        TableColumn<Institution, String> colSlug = new TableColumn<>("Slug");
        colSlug.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSlug()));
        colSlug.setMaxWidth(120);

        TableColumn<Institution, String> colDominio = new TableColumn<>("Dominio FQDN");
        colDominio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDominio()));

        TableColumn<Institution, String> colVps = new TableColumn<>("VPS Asociado");
        colVps.setCellValueFactory(c -> new SimpleStringProperty(
                vpsNombres.getOrDefault(c.getValue().getVpsId(), "VPS #" + c.getValue().getVpsId())));

        TableColumn<Institution, String> colRepo = new TableColumn<>("Repositorio Git");
        colRepo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getRepoGit() == null || c.getValue().getRepoGit().isBlank() ? "(Por defecto)" : c.getValue().getRepoGit()));

        TableColumn<Institution, Node> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(c -> {
            InstitutionStatus st = c.getValue().getEstado();
            String clase = st == InstitutionStatus.INSTALADA ? "exito" : (st == InstitutionStatus.ERROR ? "error" : "pendiente");
            return new javafx.beans.property.SimpleObjectProperty<>(UiHelper.badge(st.name(), clase));
        });

        TableColumn<Institution, String> colCommit = new TableColumn<>("Commit Desplegado");
        colCommit.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCommitDesplegado() == null || c.getValue().getCommitDesplegado().isBlank() ? "-" : c.getValue().getCommitDesplegado()));

        TableColumn<Institution, String> colCheck = new TableColumn<>("Checkpoint");
        colCheck.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCheckpoint() == null || c.getValue().getCheckpoint().isBlank() ? "-" : c.getValue().getCheckpoint()));

        tabla.getColumns().addAll(colId, colNombre, colSlug, colDominio, colVps, colRepo, colEstado, colCommit, colCheck);

        contenedor.getChildren().addAll(cabecera, barraAcciones, tabla);
    }

    private void abrirWeb(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            log.error("No se pudo abrir el navegador: {}", e.getMessage());
        }
    }

    private void eliminarInstitucion() {
        Institution inst = tabla.getSelectionModel().getSelectedItem();
        if (inst == null) {
            UiHelper.alertaAdvertencia("Selección requerida", null, "Selecciona la institución que deseas eliminar.");
            return;
        }

        boolean confirma = UiHelper.confirmar("Eliminar Institución", "¿Deseas eliminar '" + inst.getNombre() + "'?",
                "Se borrarán sus metadatos del registro local. No se borrarán los archivos remotos del VPS.");
        if (confirma) {
            try {
                servicios.app().instituciones.eliminar(inst.getId());
                alMostrar();
                UiHelper.alertaExito("Institución Eliminada", "Registro eliminado correctamente.");
            } catch (Exception e) {
                UiHelper.alertaError("Error", e.getMessage());
            }
        }
    }

    private void abrirDialogo(Institution inst) {
        boolean esNueva = (inst == null);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(esNueva ? "Registrar Institución" : "Editar Institución");
        dialog.setHeaderText(esNueva ? "Configuración de una nueva institución para bolsa laboral" : "Modificar datos de la institución");
        dialog.getDialogPane().getStyleClass().add("dialogo-moderno");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(16));

        TextField txtNombre = new TextField(inst != null ? inst.getNombre() : "");
        txtNombre.setPromptText("Ej. IESTP Carlos Cueto Fernandini");

        TextField txtSlug = new TextField(inst != null ? inst.getSlug() : "");
        txtSlug.setPromptText("Identificador corto único (ej. cueto)");

        // Autogenerar slug si es nueva
        txtNombre.textProperty().addListener((obs, old, val) -> {
            if (esNueva && (txtSlug.getText().isBlank() || txtSlug.getText().equals(CommandValidator.aSlug(old)))) {
                txtSlug.setText(CommandValidator.aSlug(val));
            }
        });

        TextField txtDominio = new TextField(inst != null ? inst.getDominio() : "");
        txtDominio.setPromptText("ej. bolsa.cueto.edu.pe");

        ComboBox<VpsServer> cbVps = new ComboBox<>();
        try {
            List<VpsServer> vpsList = servicios.app().vps.listar();
            cbVps.getItems().addAll(vpsList);
            if (!vpsList.isEmpty()) {
                if (inst != null) {
                    vpsList.stream().filter(v -> v.getId() == inst.getVpsId()).findFirst().ifPresent(cbVps::setValue);
                }
                if (cbVps.getValue() == null) {
                    cbVps.setValue(vpsList.get(0));
                }
            }
        } catch (Exception ignored) {
        }

        TextField txtRama = new TextField(inst != null ? inst.getRama() : "main");

        TextField txtRepo = new TextField(inst != null ? inst.getRepoGit() : "");
        txtRepo.setPromptText("ej. mi-usuario/mi-repo o https://github.com/...git");

        ComboBox<String> cbTipoBd = new ComboBox<>(FXCollections.observableArrayList("MYSQL", "POSTGRESQL", "SQLITE", "NINGUNA"));
        cbTipoBd.setValue(inst != null ? inst.getTipoBaseDatos() : "MYSQL");

        TextField txtNombreBd = new TextField(inst != null ? inst.getNombreBaseDatos() : "");
        txtNombreBd.setPromptText("ej. app_db");

        grid.add(new Label("Nombre completo:"), 0, 0);
        grid.add(txtNombre, 1, 0);

        grid.add(new Label("Slug identificador:"), 0, 1);
        grid.add(txtSlug, 1, 1);

        grid.add(new Label("Dominio FQDN:"), 0, 2);
        grid.add(txtDominio, 1, 2);

        grid.add(new Label("Servidor VPS:"), 0, 3);
        grid.add(cbVps, 1, 3);

        grid.add(new Label("Repositorio Git:"), 0, 4);
        grid.add(txtRepo, 1, 4);

        grid.add(new Label("Versión / Rama:"), 0, 5);
        grid.add(txtRama, 1, 5);

        grid.add(new Label("Motor de Base de Datos:"), 0, 6);
        grid.add(cbTipoBd, 1, 6);

        grid.add(new Label("Nombre Base de Datos:"), 0, 7);
        grid.add(txtNombreBd, 1, 7);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            String nombre = txtNombre.getText().trim();
            String slug = txtSlug.getText().trim().toLowerCase();
            String dominio = txtDominio.getText().trim().toLowerCase();
            VpsServer vps = cbVps.getValue();

            if (nombre.isBlank() || slug.isBlank() || dominio.isBlank() || vps == null) {
                UiHelper.alertaError("Datos Incompletos", "Nombre, Slug, Dominio y Servidor VPS son campos obligatorios.");
                return;
            }

            try {
                Institution target = esNueva ? new Institution() : inst;
                target.setNombre(nombre);
                target.setSlug(slug);
                target.setDominio(dominio);
                target.setVpsId(vps.getId());
                target.setRepoGit(txtRepo.getText().trim());
                target.setRama(txtRama.getText().trim());
                target.setTipoBaseDatos(cbTipoBd.getValue());
                target.setNombreBaseDatos(txtNombreBd.getText().trim());

                if (esNueva) {
                    target.setEstado(InstitutionStatus.PENDIENTE);
                    servicios.app().instituciones.guardar(target);
                } else {
                    servicios.app().instituciones.actualizar(target);
                }

                alMostrar();
                UiHelper.alertaExito("Institución Guardada", "La institución '" + nombre + "' fue guardada.");
            } catch (Exception e) {
                UiHelper.alertaError("Error al Guardar", e.getMessage());
            }
        }
    }

    @Override
    public Node raiz() {
        return contenedor;
    }

    @Override
    public void alMostrar() {
        try {
            List<VpsServer> listaVps = servicios.app().vps.listar();
            vpsNombres.clear();
            for (VpsServer v : listaVps) {
                vpsNombres.put(v.getId(), v.getNombre());
            }

            List<Institution> lista = servicios.app().instituciones.listar();
            Platform.runLater(() -> tabla.getItems().setAll(lista));
        } catch (Exception e) {
            log.error("Error al refrescar instituciones: {}", e.getMessage(), e);
        }
    }
}
