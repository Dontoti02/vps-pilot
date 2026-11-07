package com.bolsadeploy.ui;

import com.bolsadeploy.application.AppContext;
import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.ui.navegacion.ContenedorPrincipal;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Punto de entrada principal de BolsaDeploy Manager en Windows.
 */
public class BolsaDeployApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(BolsaDeployApp.class);

    private AppContext appContext;
    private Servicios servicios;
    private ContenedorPrincipal contenedor;
    private StackPane raizApp;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        try {
            appContext = AppContext.abrir();
        } catch (Throwable e) {
            log.error("Fallo crítico al inicializar la base de datos local SQLite: {}", e.getMessage(), e);
            try {
                javax.swing.JOptionPane.showMessageDialog(null,
                        "Fallo al inicializar VPSPilot:\n" + e.getMessage(),
                        "Error de Inicialización",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
            }
            Platform.exit();
            return;
        }

        servicios = new Servicios(appContext, this);
        contenedor = new ContenedorPrincipal(servicios);

        raizApp = new StackPane();
        raizApp.getChildren().add(contenedor);

        scene = new Scene(raizApp, 1280, 820);
        aplicarTema(appContext.ajustes.getTema());

        // Cargar icono de la aplicación en la barra de título y barra de tareas
        try {
            var iconStream = getClass().getResourceAsStream("/ui/logo_icon.png");
            if (iconStream != null) {
                stage.getIcons().add(new javafx.scene.image.Image(iconStream));
            }
        } catch (Exception ignored) {
        }

        stage.setTitle("VPSPilot — Orquestador de Despliegues");
        stage.setMinWidth(1100);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();

        // Flujo de seguridad inicial: Login de Windows requerido
        if (appContext.ajustes.isRequerirLoginWindows()) {
            mostrarLoginWindows();
        } else if (!appContext.ajustes.isOnboardingVisto()) {
            mostrarOnboarding();
        }
    }

    public void mostrarLoginWindows() {
        if (raizApp == null) return;
        boolean yaMostrado = raizApp.getChildren().stream().anyMatch(n -> n instanceof com.bolsadeploy.ui.componentes.PantallaLoginWindows);
        if (!yaMostrado) {
            var login = new com.bolsadeploy.ui.componentes.PantallaLoginWindows(raizApp, appContext.ajustes, () -> {
                // Al desbloquear exitosamente:
                if (!appContext.ajustes.isOnboardingVisto()) {
                    mostrarOnboarding();
                }
            });
            raizApp.getChildren().add(login);
            login.animarEntrada();
        }
    }

    public void mostrarOnboarding() {
        if (raizApp == null) return;
        boolean yaMostrado = raizApp.getChildren().stream().anyMatch(n -> n instanceof com.bolsadeploy.ui.componentes.OnboardingOverlay);
        if (!yaMostrado) {
            var overlay = new com.bolsadeploy.ui.componentes.OnboardingOverlay(raizApp, appContext.ajustes, () -> {
                appContext.ajustes.setOnboardingVisto(true);
                new Thread(() -> {
                    try {
                        appContext.ajustesRepo.guardar(appContext.ajustes);
                    } catch (Exception e) {
                        log.warn("No se pudo guardar preferencia de onboarding: {}", e.getMessage());
                    }
                }, "save-onboarding-setting").start();
            });
            raizApp.getChildren().add(overlay);
            overlay.animarEntrada();
        }
    }

    public void cambiarTema(String tema) {
        aplicarTema(tema);
        if (contenedor != null) {
            contenedor.actualizarLogo(tema);
        }
    }

    private void aplicarTema(String tema) {
        if (scene == null) return;
        scene.getStylesheets().clear();

        String cssResource = AppSettings.TEMA_CLARO.equalsIgnoreCase(tema)
                ? "/ui/tema-claro.css"
                : "/ui/tema-oscuro.css";

        var url = getClass().getResource(cssResource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        } else {
            log.warn("No se encontró la hoja de estilos: {}", cssResource);
        }
    }

    public void navegarA(String vista) {
        if (contenedor != null) {
            contenedor.navegarA(vista);
        }
    }

    public void seleccionarDespliegue(Institution inst) {
        if (contenedor != null) {
            contenedor.seleccionarDespliegue(inst);
        }
    }

    @Override
    public void stop() {
        if (appContext != null) {
            try {
                appContext.close();
            } catch (Exception e) {
                log.warn("Error al cerrar AppContext: {}", e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
