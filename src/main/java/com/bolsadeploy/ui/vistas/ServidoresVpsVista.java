package com.bolsadeploy.ui.vistas;

import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.domain.ConnectionStatus;
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

import java.util.List;
import java.util.Optional;

/**
 * Gestión completa de servidores VPS: registro, edición, credenciales SSH y pruebas de conectividad.
 */
public class ServidoresVpsVista implements Vista {

    private static final Logger log = LoggerFactory.getLogger(ServidoresVpsVista.class);

    private final Servicios servicios;
    private final VBox contenedor;
    private TableView<VpsServer> tabla;
    private Button btnProbar;
    private ProgressIndicator spinnerProbar;

    public ServidoresVpsVista(Servicios servicios) {
        this.servicios = servicios;

        contenedor = new VBox(16);
        contenedor.setPadding(new Insets(24));
        contenedor.setMaxWidth(1300);

        construirUi();
    }

    private void construirUi() {
        VBox cabecera = UiHelper.cabeceraSeccion("Servidores VPS",
                "Administra los servidores Ubuntu donde se alojan las instancias de bolsa laboral.");

        // Botones de acción
        HBox barraAcciones = new HBox(10);
        barraAcciones.setAlignment(Pos.CENTER_LEFT);

        Button btnNuevo = new Button("+ Registrar Servidor");
        btnNuevo.getStyleClass().add("boton-primario");
        btnNuevo.setOnAction(ev -> abrirDialogoEdicion(null));

        Button btnEditar = new Button("Editar");
        btnEditar.setOnAction(ev -> {
            VpsServer s = tabla.getSelectionModel().getSelectedItem();
            if (s != null) abrirDialogoEdicion(s);
            else UiHelper.alertaAdvertencia("Selección requerida", null, "Selecciona un servidor de la tabla.");
        });

        Button btnEliminar = new Button("Eliminar");
        btnEliminar.getStyleClass().add("boton-peligro");
        btnEliminar.setOnAction(ev -> eliminarServidor());

        btnProbar = new Button("🔌 Probar Conexión SSH");
        btnProbar.setOnAction(ev -> probarConexionSsh());

        spinnerProbar = new ProgressIndicator();
        spinnerProbar.setPrefSize(22, 22);
        spinnerProbar.setMaxSize(22, 22);
        spinnerProbar.setVisible(false);

        Button btnRefrescar = new Button("Refrescar");
        btnRefrescar.setOnAction(ev -> alMostrar());

        barraAcciones.getChildren().addAll(btnNuevo, btnEditar, btnEliminar, btnProbar, spinnerProbar, btnRefrescar);

        // Tabla de servidores
        tabla = new TableView<>();
        tabla.setPrefHeight(450);
        tabla.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tabla, Priority.ALWAYS);

        TableColumn<VpsServer, String> colId = new TableColumn<>("ID");
        colId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getId())));
        colId.setMaxWidth(50);

        TableColumn<VpsServer, String> colNombre = new TableColumn<>("Nombre");
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));

        TableColumn<VpsServer, String> colHost = new TableColumn<>("Host : Puerto");
        colHost.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getHost() + ":" + c.getValue().getPuerto()));

        TableColumn<VpsServer, String> colUser = new TableColumn<>("Usuario");
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsuario()));

        TableColumn<VpsServer, String> colAuth = new TableColumn<>("Autenticación");
        colAuth.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipoAuth().name()));

        TableColumn<VpsServer, Node> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(c -> {
            ConnectionStatus st = c.getValue().getEstado();
            String clase = st == ConnectionStatus.CONECTADO ? "exito" : (st == ConnectionStatus.ERROR ? "error" : "pendiente");
            return new javafx.beans.property.SimpleObjectProperty<>(UiHelper.badge(st.name(), clase));
        });

        TableColumn<VpsServer, String> colResultado = new TableColumn<>("Último Resultado / Diagnóstico");
        colResultado.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getUltimoResultado() == null ? "-" : c.getValue().getUltimoResultado()));

        tabla.getColumns().addAll(colId, colNombre, colHost, colUser, colAuth, colEstado, colResultado);

        contenedor.getChildren().addAll(cabecera, barraAcciones, tabla);
    }

    private void probarConexionSsh() {
        VpsServer s = tabla.getSelectionModel().getSelectedItem();
        if (s == null) {
            UiHelper.alertaAdvertencia("Selección requerida", null, "Selecciona un servidor VPS para probar la conexión.");
            return;
        }

        btnProbar.setDisable(true);
        btnProbar.setText("⏳ Probando...");
        spinnerProbar.setVisible(true);

        new Thread(() -> {
            try {
                var resultado = servicios.mantenimiento().probarConexion(s, servicios.prompt());
                Platform.runLater(() -> {
                    btnProbar.setDisable(false);
                    btnProbar.setText("🔌 Probar Conexión SSH");
                    spinnerProbar.setVisible(false);
                    alMostrar();
                    if (resultado.ok()) {
                        UiHelper.alertaExito("Conexión SSH Exitosa", resultado.detalle());
                    } else {
                        UiHelper.alertaError("Fallo de Conexión SSH", resultado.detalle());
                    }
                });
            } catch (Throwable t) {
                log.error("Error al probar conexión SSH con {}: {}", s.getHost(), t.getMessage(), t);
                Platform.runLater(() -> {
                    btnProbar.setDisable(false);
                    btnProbar.setText("🔌 Probar Conexión SSH");
                    spinnerProbar.setVisible(false);
                    alMostrar();
                    UiHelper.alertaError("Error Inesperado", "Ocurrió un error al probar la conexión: " + t.getMessage());
                });
            }
        }, "ssh-test-" + s.getHost()).start();
    }

    private void eliminarServidor() {
        VpsServer s = tabla.getSelectionModel().getSelectedItem();
        if (s == null) {
            UiHelper.alertaAdvertencia("Selección requerida", null, "Selecciona el servidor que deseas eliminar.");
            return;
        }

        boolean confirma = UiHelper.confirmar("Eliminar Servidor", "¿Estás seguro de eliminar el servidor '" + s.getNombre() + "'?",
                "Se borrarán sus datos locales y sus credenciales guardadas. Las instalaciones remotas no se tocarán.");
        if (confirma) {
            try {
                servicios.app().vps.eliminar(s.getId());
                servicios.app().credenciales.eliminar(s.credencialId());
                alMostrar();
                UiHelper.alertaExito("Servidor Eliminado", "El servidor ha sido eliminado del registro local.");
            } catch (Exception e) {
                UiHelper.alertaError("Error", "No se pudo eliminar el servidor: " + e.getMessage());
            }
        }
    }

    private void abrirDialogoEdicion(VpsServer server) {
        boolean esNuevo = (server == null);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(esNuevo ? "Registrar Nuevo Servidor VPS" : "Editar Servidor VPS");
        dialog.setHeaderText(esNuevo ? "Ingresa los datos de acceso al servidor Ubuntu" : "Modifica los datos del servidor");
        dialog.getDialogPane().getStyleClass().add("dialogo-moderno");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(16));

        TextField txtNombre = new TextField(server != null ? server.getNombre() : "");
        txtNombre.setPromptText("Ej. VPS Producción Principal");

        TextField txtHost = new TextField(server != null ? server.getHost() : "");
        txtHost.setPromptText("IP pública o dominio (ej. 198.51.100.24)");

        TextField txtPuerto = new TextField(server != null ? String.valueOf(server.getPuerto()) : "22");

        TextField txtUsuario = new TextField(server != null ? server.getUsuario() : "root");

        ComboBox<AuthType> cbAuth = new ComboBox<>();
        cbAuth.getItems().addAll(AuthType.PASSWORD, AuthType.KEY);
        cbAuth.setValue(server != null ? server.getTipoAuth() : AuthType.PASSWORD);

        PasswordField txtPass = new PasswordField();
        txtPass.setPromptText(esNuevo ? "Contraseña o clave privada" : "(Dejar vacío para no cambiar)");

        TextField txtGrupo = new TextField(server != null ? server.getGrupo() : "");
        txtGrupo.setPromptText("Opcional: grupo o etiqueta");

        grid.add(new Label("Nombre identificador:"), 0, 0);
        grid.add(txtNombre, 1, 0);

        grid.add(new Label("Host (IP o FQDN):"), 0, 1);
        grid.add(txtHost, 1, 1);

        grid.add(new Label("Puerto SSH:"), 0, 2);
        grid.add(txtPuerto, 1, 2);

        grid.add(new Label("Usuario SSH:"), 0, 3);
        grid.add(txtUsuario, 1, 3);

        grid.add(new Label("Tipo de autenticación:"), 0, 4);
        grid.add(cbAuth, 1, 4);

        grid.add(new Label("Contraseña / Clave:"), 0, 5);
        grid.add(txtPass, 1, 5);

        grid.add(new Label("Grupo / Etiqueta:"), 0, 6);
        grid.add(txtGrupo, 1, 6);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            String nombre = txtNombre.getText().trim();
            String host = txtHost.getText().trim();
            String user = txtUsuario.getText().trim();
            String pass = txtPass.getText();

            if (nombre.isBlank() || host.isBlank() || user.isBlank()) {
                UiHelper.alertaError("Datos Incompletos", "Nombre, Host y Usuario son obligatorios.");
                return;
            }
            if (esNuevo && pass.isBlank()) {
                UiHelper.alertaError("Credencial Requerida", "Debes ingresar una contraseña o clave privada para un servidor nuevo.");
                return;
            }

            int puerto = 22;
            try {
                puerto = Integer.parseInt(txtPuerto.getText().trim());
            } catch (Exception ignored) {
            }

            try {
                VpsServer target = esNuevo ? new VpsServer() : server;
                target.setNombre(nombre);
                target.setHost(host);
                target.setPuerto(puerto);
                target.setUsuario(user);
                target.setTipoAuth(cbAuth.getValue());
                target.setGrupo(txtGrupo.getText().trim());

                if (esNuevo) {
                    target.setEstado(ConnectionStatus.SIN_COMPROBAR);
                    long id = servicios.app().vps.guardar(target);
                    target.setId(id);
                } else {
                    servicios.app().vps.actualizar(target);
                }

                if (!pass.isBlank()) {
                    servicios.app().credenciales.guardar(target.credencialId(), pass);
                }

                alMostrar();
                UiHelper.alertaExito("Servidor Guardado", "El servidor '" + nombre + "' fue guardado correctamente.");
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
            List<VpsServer> lista = servicios.app().vps.listar();
            Platform.runLater(() -> tabla.getItems().setAll(lista));
        } catch (Exception e) {
            log.error("Error al listar VPS: {}", e.getMessage(), e);
        }
    }
}
