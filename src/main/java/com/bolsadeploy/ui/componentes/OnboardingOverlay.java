package com.bolsadeploy.ui.componentes;

import com.bolsadeploy.domain.AppSettings;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla de bienvenida interactiva y animada para VPSPilot.
 * Se presenta sobre el contenedor principal con transiciones suaves y
 * soporte total para tema claro y tema oscuro.
 */
public class OnboardingOverlay extends StackPane {

    private final Pane parentContainer;
    private final AppSettings settings;
    private final Runnable onDismiss;

    private final VBox card;
    private final StackPane slideContainer;
    private final HBox dotsBox;
    private final Button btnAnterior;
    private final Button btnSiguiente;
    private final Button btnSaltar;

    private int pasoActual = 0;
    private final int totalPasos = 3;
    private final List<Circle> dots = new ArrayList<>();

    public OnboardingOverlay(Pane parentContainer, AppSettings settings, Runnable onDismiss) {
        this.parentContainer = parentContainer;
        this.settings = settings;
        this.onDismiss = onDismiss;

        getStyleClass().add("onboarding-overlay");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(24));

        // Tarjeta central modal
        card = new VBox(18);
        card.getStyleClass().add("onboarding-card");
        card.setMaxWidth(760);
        card.setPrefWidth(760);
        card.setMinHeight(520);
        card.setPrefHeight(520);
        card.setPadding(new Insets(28, 32, 24, 32));

        // 1. Barra superior: marca y botón cerrar (✕)
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label lblBadge = new Label("✨ GUÍA DE INICIO RÁPIDO");
        lblBadge.getStyleClass().add("onboarding-pill");

        Region spacerTop = new Region();
        HBox.setHgrow(spacerTop, Priority.ALWAYS);

        Button btnCerrar = new Button("✕");
        btnCerrar.getStyleClass().add("onboarding-close-btn");
        btnCerrar.setOnAction(ev -> cerrarConAnimacion());

        topBar.getChildren().addAll(lblBadge, spacerTop, btnCerrar);

        // 2. Contenedor de diapositivas animadas
        slideContainer = new StackPane();
        VBox.setVgrow(slideContainer, Priority.ALWAYS);

        // 3. Barra inferior de navegación
        HBox bottomBar = new HBox(12);
        bottomBar.setAlignment(Pos.CENTER_LEFT);

        dotsBox = new HBox(8);
        dotsBox.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < totalPasos; i++) {
            final int targetPaso = i;
            Circle dot = new Circle(5);
            dot.getStyleClass().add("onboarding-dot");
            dot.setOnMouseClicked(ev -> irAPaso(targetPaso));
            dots.add(dot);
            dotsBox.getChildren().add(dot);
        }

        btnSaltar = new Button("Saltar introducción");
        btnSaltar.getStyleClass().add("onboarding-btn-link");
        btnSaltar.setOnAction(ev -> cerrarConAnimacion());

        Region spacerBottom = new Region();
        HBox.setHgrow(spacerBottom, Priority.ALWAYS);

        btnAnterior = new Button("← Anterior");
        btnAnterior.getStyleClass().add("boton-secundario");
        btnAnterior.setOnAction(ev -> anteriorPaso());

        btnSiguiente = new Button("Siguiente →");
        btnSiguiente.getStyleClass().add("boton-primario");
        btnSiguiente.setOnAction(ev -> siguientePaso());

        bottomBar.getChildren().addAll(dotsBox, btnSaltar, spacerBottom, btnAnterior, btnSiguiente);

        card.getChildren().addAll(topBar, slideContainer, bottomBar);
        getChildren().add(card);

        // Cargar primer paso
        actualizarPaso(false);
    }

    public void animarEntrada() {
        setOpacity(0.0);
        card.setScaleX(0.88);
        card.setScaleY(0.88);

        FadeTransition fade = new FadeTransition(Duration.millis(350), this);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(350), card);
        scale.setFromX(0.88);
        scale.setFromY(0.88);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition anim = new ParallelTransition(fade, scale);
        anim.play();
    }

    private void cerrarConAnimacion() {
        FadeTransition fade = new FadeTransition(Duration.millis(250), this);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(250), card);
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
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        anim.play();
    }

    private void siguientePaso() {
        if (pasoActual < totalPasos - 1) {
            pasoActual++;
            actualizarPaso(true);
        } else {
            cerrarConAnimacion();
        }
    }

    private void anteriorPaso() {
        if (pasoActual > 0) {
            pasoActual--;
            actualizarPaso(false);
        }
    }

    private void irAPaso(int paso) {
        if (paso != pasoActual) {
            boolean haciaAdelante = paso > pasoActual;
            pasoActual = paso;
            actualizarPaso(haciaAdelante);
        }
    }

    private void actualizarPaso(boolean haciaAdelante) {
        // Actualizar indicadores (dots)
        for (int i = 0; i < totalPasos; i++) {
            Circle d = dots.get(i);
            d.getStyleClass().remove("onboarding-dot-active");
            if (i == pasoActual) {
                d.getStyleClass().add("onboarding-dot-active");
            }
        }

        // Botones
        btnAnterior.setVisible(pasoActual > 0);
        btnAnterior.setManaged(pasoActual > 0);

        btnSaltar.setVisible(pasoActual < totalPasos - 1);
        btnSaltar.setManaged(pasoActual < totalPasos - 1);

        if (pasoActual == totalPasos - 1) {
            btnSiguiente.setText("🚀 ¡Empezar a Usar VPSPilot!");
            btnSiguiente.getStyleClass().remove("boton-secundario");
            if (!btnSiguiente.getStyleClass().contains("boton-primario")) {
                btnSiguiente.getStyleClass().add("boton-primario");
            }
        } else {
            btnSiguiente.setText("Siguiente →");
        }

        Node nuevoContenido = switch (pasoActual) {
            case 0 -> crearPaso1();
            case 1 -> crearPaso2();
            case 2 -> crearPaso3();
            default -> new Pane();
        };

        // Transición animada entre pasos
        nuevoContenido.setOpacity(0.0);
        double offsetInicial = haciaAdelante ? 30 : -30;
        nuevoContenido.setTranslateX(offsetInicial);

        slideContainer.getChildren().setAll(nuevoContenido);

        FadeTransition ft = new FadeTransition(Duration.millis(260), nuevoContenido);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);

        TranslateTransition tt = new TranslateTransition(Duration.millis(260), nuevoContenido);
        tt.setFromX(offsetInicial);
        tt.setToX(0);
        tt.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(ft, tt).play();
    }

    // -------------------------------------------------------------------------
    // Vistas de los 3 Pasos
    // -------------------------------------------------------------------------

    private Node crearPaso1() {
        VBox box = new VBox(16);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(10, 20, 10, 20));

        // Logo centrado animado
        ImageView imgLogo = cargarLogo(280, 80);

        Label lblTitulo = new Label("Bienvenido a VPSPilot");
        lblTitulo.getStyleClass().add("onboarding-titulo");

        Label lblSub = new Label("El orquestador definitivo de despliegues y administración para VPS Ubuntu");
        lblSub.getStyleClass().add("onboarding-subtitulo");
        lblSub.setWrapText(true);

        Label lblDesc = new Label(
                "VPSPilot automatiza todo el ciclo de vida de tus aplicaciones Laravel 12 y Vite 7: "
                        + "aprovisionamiento de Nginx, MySQL, PHP 8.3, certificados SSL Let's Encrypt, "
                        + "actualizaciones atómicas con rollback y monitoreo en vivo sin necesidad de agentes residentes."
        );
        lblDesc.getStyleClass().add("onboarding-texto");
        lblDesc.setWrapText(true);

        // Insignias destacadas
        HBox badges = new HBox(10);
        badges.setAlignment(Pos.CENTER);
        badges.getChildren().addAll(
                crearBadge("⚡ 11 Etapas Automatizadas"),
                crearBadge("🛡️ Cifrado Local DPAPI"),
                crearBadge("🔒 SSH Ed25519 Nativo"),
                crearBadge("🚀 Despliegues con 1 Clic")
        );

        box.getChildren().addAll(imgLogo, lblTitulo, lblSub, lblDesc, badges);
        return box;
    }

    private Node crearPaso2() {
        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10, 24, 10, 24));

        Label lblHeader = new Label("🖥️ Servidores y Conexión Segura");
        lblHeader.getStyleClass().add("onboarding-titulo");

        Label lblSub = new Label("Cero agentes en el VPS. Máxima seguridad y verificación en cada paquete.");
        lblSub.getStyleClass().add("onboarding-subtitulo");

        VBox filas = new VBox(12);
        filas.setPadding(new Insets(8, 0, 8, 0));

        filas.getChildren().addAll(
                crearFilaCaracteristica(
                        "🔑",
                        "Autenticación Flexible y Robusta",
                        "Conéctate mediante claves privadas (Ed25519, RSA, ECDSA) o contraseñas seguras con soporte automático para PAM (keyboard-interactive)."
                ),
                crearFilaCaracteristica(
                        "🛡️",
                        "Defensa Activa Man-in-the-Middle",
                        "Verificación estricta de huellas SHA256 con registro en known_hosts. Si la clave del servidor cambia, el sistema te alerta al instante."
                ),
                crearFilaCaracteristica(
                        "🔐",
                        "Cifrado de Credenciales con DPAPI de Windows",
                        "Tus claves y contraseñas nunca viajan a la nube ni se almacenan en texto plano; quedan protegidas por la criptografía de tu cuenta de Windows."
                ),
                crearFilaCaracteristica(
                        "🩺",
                        "Telemetría en Vivo Bajo Demanda",
                        "Consulta el uso real de CPU, memoria RAM, almacenamiento y servicios (Nginx, PHP-FPM, MySQL) en tiempo real con solo presionar un botón."
                )
        );

        box.getChildren().addAll(lblHeader, lblSub, filas);
        return box;
    }

    private Node crearPaso3() {
        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10, 24, 10, 24));

        Label lblHeader = new Label("🚀 Despliegues, Respaldos y Rollbacks");
        lblHeader.getStyleClass().add("onboarding-titulo");

        Label lblSub = new Label("Gestiona múltiples instituciones con tranquilidad absoluta.");
        lblSub.getStyleClass().add("onboarding-subtitulo");

        VBox filas = new VBox(12);
        filas.setPadding(new Insets(8, 0, 8, 0));

        filas.getChildren().addAll(
                crearFilaCaracteristica(
                        "📦",
                        "Asistente de Despliegue en 5 Pasos",
                        "Asocia tu institución a un servidor VPS, asigna slug, base de datos y dominio FQDN. El asistente ejecuta las 11 etapas de configuración sin errores manuales."
                ),
                crearFilaCaracteristica(
                        "🔄",
                        "Actualizaciones Atómicas y Rollback Inmediato",
                        "Inspecciona diferencias contra GitHub, detecta migraciones de riesgo y actualiza con un solo clic. Si una migración falla, el rollback restaura tu estado previo."
                ),
                crearFilaCaracteristica(
                        "💾",
                        "Copias de Seguridad y Restauración Segura",
                        "Genera copias integrales (base de datos + archivos + configuración) y descárgalas por SFTP a tu PC para tener tus datos siempre protegidos."
                ),
                crearFilaCaracteristica(
                        "📜",
                        "Registro de Auditoría Detallado",
                        "Historial transparente de cada comando ejecutado, tiempos de respuesta, código de salida y mensajes claros en español."
                )
        );

        box.getChildren().addAll(lblHeader, lblSub, filas);
        return box;
    }

    // -------------------------------------------------------------------------
    // Componentes auxiliares
    // -------------------------------------------------------------------------

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

    private Label crearBadge(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("onboarding-feature-badge");
        return l;
    }

    private HBox crearFilaCaracteristica(String icono, String titulo, String descripcion) {
        HBox fila = new HBox(14);
        fila.setAlignment(Pos.TOP_LEFT);

        Label lblIcono = new Label(icono);
        lblIcono.getStyleClass().add("onboarding-feature-icon");

        VBox textos = new VBox(2);
        Label lblTit = new Label(titulo);
        lblTit.getStyleClass().add("onboarding-feature-title");

        Label lblDesc = new Label(descripcion);
        lblDesc.getStyleClass().add("onboarding-feature-desc");
        lblDesc.setWrapText(true);

        textos.getChildren().addAll(lblTit, lblDesc);
        HBox.setHgrow(textos, Priority.ALWAYS);

        fila.getChildren().addAll(lblIcono, textos);
        return fila;
    }
}

