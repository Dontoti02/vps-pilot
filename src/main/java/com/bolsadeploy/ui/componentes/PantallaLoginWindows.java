package com.bolsadeploy.ui.componentes;

import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.security.WindowsAuthenticator;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Pantalla de autenticación inicial vinculada a la cuenta de Windows del usuario.
 * Garantiza que solo el propietario de la sesión de Windows pueda abrir VPSPilot.
 */
public class PantallaLoginWindows extends StackPane {

    private final Pane parentContainer;
    private final AppSettings settings;
    private final Runnable onSuccess;

    private final VBox card;
    private final PasswordField txtPassword;
    private final Label lblError;
    private final Button btnLogin;
    private final Button btnWindowsHello;
    private final ProgressIndicator spinner;

    public PantallaLoginWindows(Pane parentContainer, AppSettings settings, Runnable onSuccess) {
        this.parentContainer = parentContainer;
        this.settings = settings;
        this.onSuccess = onSuccess;

        getStyleClass().add("login-overlay");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(24));

        // Tarjeta central de login
        card = new VBox(18);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(460);
        card.setPrefWidth(460);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(36, 36, 32, 36));

        // 1. Logo superior
        ImageView ivLogo = cargarLogo(220, 60);

        // 2. Avatar de usuario e información de la cuenta
        VBox userBox = new VBox(6);
        userBox.setAlignment(Pos.CENTER);

        Label lblAvatar = new Label("👤");
        lblAvatar.getStyleClass().add("login-avatar");

        String nombreUsuario = WindowsAuthenticator.obtenerUsuarioActual();
        Label lblUsuario = new Label(nombreUsuario);
        lblUsuario.getStyleClass().add("login-username");

        String dominio = WindowsAuthenticator.obtenerDominioActual();
        Label lblDominio = new Label("Equipo: " + dominio);
        lblDominio.getStyleClass().add("login-domain");

        userBox.getChildren().addAll(lblAvatar, lblUsuario, lblDominio);

        // 3. Textos explicativos
        Label lblTitulo = new Label("Acceso Protegido");
        lblTitulo.getStyleClass().add("login-titulo");

        Label lblInstruccion = new Label("Ingresa los datos de cuenta con los que inicias sesión en esta PC para desbloquear VPSPilot.");
        lblInstruccion.getStyleClass().add("login-desc");
        lblInstruccion.setWrapText(true);

        // 4. Campo de contraseña
        VBox inputGroup = new VBox(8);
        inputGroup.setAlignment(Pos.CENTER_LEFT);

        Label lblPassTitle = new Label("Contraseña de Windows:");
        lblPassTitle.getStyleClass().add("login-label-field");

        txtPassword = new PasswordField();
        txtPassword.setPromptText("Escribe tu contraseña de inicio de sesión...");
        txtPassword.getStyleClass().add("login-password-field");
        txtPassword.setMaxWidth(Double.MAX_VALUE);

        lblError = new Label();
        lblError.getStyleClass().add("login-error-label");
        lblError.setVisible(false);
        lblError.setManaged(false);
        lblError.setWrapText(true);

        inputGroup.getChildren().addAll(lblPassTitle, txtPassword, lblError);

        // 5. Botones de acción
        VBox actionBox = new VBox(10);
        actionBox.setAlignment(Pos.CENTER);
        actionBox.setPadding(new Insets(6, 0, 0, 0));

        spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        spinner.setMaxSize(20, 20);
        spinner.setVisible(false);
        spinner.setManaged(false);

        btnLogin = new Button("🔓 Desbloquear VPSPilot");
        btnLogin.getStyleClass().add("boton-primario");
        btnLogin.setMaxWidth(Double.MAX_VALUE);
        btnLogin.setOnAction(ev -> procesarLoginPassword());

        btnWindowsHello = new Button("🔑 Usar PIN de Windows o Windows Hello");
        btnWindowsHello.getStyleClass().add("boton-secundario");
        btnWindowsHello.setMaxWidth(Double.MAX_VALUE);
        btnWindowsHello.setOnAction(ev -> procesarLoginWindowsHello());

        Button btnSalir = new Button("Salir del programa");
        btnSalir.getStyleClass().add("login-btn-link");
        btnSalir.setOnAction(ev -> Platform.exit());

        HBox loginRow = new HBox(8);
        loginRow.setAlignment(Pos.CENTER);
        loginRow.getChildren().addAll(btnLogin, spinner);

        actionBox.getChildren().addAll(loginRow, btnWindowsHello, btnSalir);

        // Tecla Enter para enviar formulario
        txtPassword.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ENTER) {
                procesarLoginPassword();
            }
        });

        card.getChildren().addAll(ivLogo, userBox, lblTitulo, lblInstruccion, inputGroup, actionBox);
        getChildren().add(card);

        // Foco automático en el campo de contraseña
        Platform.runLater(txtPassword::requestFocus);
    }

    public void animarEntrada() {
        setOpacity(0.0);
        card.setScaleX(0.90);
        card.setScaleY(0.90);

        FadeTransition fade = new FadeTransition(Duration.millis(300), this);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(300), card);
        scale.setFromX(0.90);
        scale.setFromY(0.90);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, scale).play();
    }

    private void procesarLoginPassword() {
        String pwd = txtPassword.getText();
        if (pwd.isEmpty()) {
            mostrarError("Por favor escribe tu contraseña de Windows.");
            return;
        }

        bloquearUi(true);
        ocultarError();

        String usuario = WindowsAuthenticator.obtenerUsuarioActual();

        new Thread(() -> {
            boolean esValido = WindowsAuthenticator.verificarPassword(usuario, pwd);
            Platform.runLater(() -> {
                bloquearUi(false);
                if (esValido) {
                    animarSalidaYDesbloquear();
                } else {
                    mostrarError("Contraseña incorrecta para el usuario '" + usuario + "'. Si utilizas un PIN, usa el botón 'Usar PIN o Windows Hello'.");
                    animarSacudidaError();
                    txtPassword.selectAll();
                    txtPassword.requestFocus();
                }
            });
        }, "win-auth-worker").start();
    }

    private void procesarLoginWindowsHello() {
        bloquearUi(true);
        ocultarError();

        new Thread(() -> {
            boolean esValido = WindowsAuthenticator.autenticarConWindowsPrompt(
                    "VPSPilot — Seguridad de Windows",
                    "Confirma tu identidad de Windows (PIN, contraseña o huella) para acceder a VPSPilot."
            );

            Platform.runLater(() -> {
                bloquearUi(false);
                if (esValido) {
                    animarSalidaYDesbloquear();
                } else {
                    mostrarError("No se completó la verificación con Windows. Vuelve a intentarlo.");
                }
            });
        }, "win-hello-worker").start();
    }

    private void animarSalidaYDesbloquear() {
        FadeTransition fade = new FadeTransition(Duration.millis(260), this);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(260), card);
        scale.setFromX(1.0);
        scale.setFromY(1.0);
        scale.setToX(0.92);
        scale.setToY(0.92);
        scale.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition anim = new ParallelTransition(fade, scale);
        anim.setOnFinished(ev -> {
            if (parentContainer != null) {
                parentContainer.getChildren().remove(this);
            }
            if (onSuccess != null) {
                onSuccess.run();
            }
        });
        anim.play();
    }

    private void animarSacudidaError() {
        TranslateTransition tt = new TranslateTransition(Duration.millis(60), card);
        tt.setByX(10);
        tt.setCycleCount(4);
        tt.setAutoReverse(true);
        tt.setOnFinished(ev -> card.setTranslateX(0));
        tt.play();
    }

    private void mostrarError(String mensaje) {
        lblError.setText(mensaje);
        lblError.setVisible(true);
        lblError.setManaged(true);
    }

    private void ocultarError() {
        lblError.setVisible(false);
        lblError.setManaged(false);
    }

    private void bloquearUi(boolean bloqueado) {
        btnLogin.setDisable(bloqueado);
        btnWindowsHello.setDisable(bloqueado);
        txtPassword.setDisable(bloqueado);
        spinner.setVisible(bloqueado);
        spinner.setManaged(bloqueado);
    }

    private ImageView cargarLogo(double maxWidth, double maxHeight) {
        ImageView iv = new ImageView();
        try {
            boolean esOscuro = settings == null || AppSettings.TEMA_OSCURO.equalsIgnoreCase(settings.getTema());
            String recurso = esOscuro ? "/ui/logo_horizontal_darktheme.png" : "/ui/logo_horizontal.png";
            var stream = getClass().getResourceAsStream(recurso);
            if (stream == null) {
                stream = getClass().getResourceAsStream("/ui/logo.png");
            }
            if (stream != null) {
                Image img = new Image(stream, maxWidth, maxHeight, true, true);
                iv.setImage(img);
            }
        } catch (Exception ignored) {
        }
        return iv;
    }
}

