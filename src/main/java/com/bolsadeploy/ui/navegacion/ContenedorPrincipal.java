package com.bolsadeploy.ui.navegacion;

import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.ui.Servicios;
import com.bolsadeploy.ui.vistas.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contenedor principal con sidebar lateral responsivo y área central dinámica.
 */
public class ContenedorPrincipal extends BorderPane {

    private final Servicios servicios;
    private final Map<String, Vista> vistas = new LinkedHashMap<>();
    private final Map<String, Button> botonesNav = new LinkedHashMap<>();

    private final DespliegueWizardVista vistaDespliegue;
    private ImageView ivLogo;

    public ContenedorPrincipal(Servicios servicios) {
        this.servicios = servicios;

        // Registrar vistas
        vistas.put("Dashboard", new DashboardVista(servicios));
        vistas.put("Servidores VPS", new ServidoresVpsVista(servicios));
        vistas.put("Instituciones", new InstitucionesVista(servicios));

        vistaDespliegue = new DespliegueWizardVista(servicios);
        vistas.put("Nuevo despliegue", vistaDespliegue);

        vistas.put("Bases de datos", new BasesDatosVista(servicios));
        vistas.put("Actualizaciones", new ActualizacionesVista(servicios));
        vistas.put("Copias", new CopiasVista(servicios));
        vistas.put("Monitoreo", new MonitoreoVista(servicios));
        vistas.put("Historial", new HistorialVista(servicios));
        vistas.put("Configuración", new ConfiguracionVista(servicios));

        setLeft(construirSidebar());

        // Vista inicial
        navegarA("Dashboard");
    }

    private VBox construirSidebar() {
        VBox sidebar = new VBox(8);
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(200);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(20, 12, 16, 12));

        // Brand / Logo VPSPilot
        HBox brandBox = new HBox(10);
        brandBox.setAlignment(Pos.CENTER_LEFT);
        brandBox.setPadding(new Insets(0, 8, 18, 8));

        ivLogo = new ImageView();
        ivLogo.setFitWidth(36);
        ivLogo.setFitHeight(36);
        ivLogo.setPreserveRatio(true);
        ivLogo.setSmooth(true);
        actualizarLogo(servicios.app().ajustes.getTema());

        VBox brandTexts = new VBox(1);
        brandTexts.setAlignment(Pos.CENTER_LEFT);

        Label lblTitulo = new Label("VPSPilot");
        lblTitulo.getStyleClass().add("sidebar-brand-title");

        Label lblSub = new Label("ORCHESTRATOR v1.1.0");
        lblSub.getStyleClass().add("sidebar-brand-sub");

        brandTexts.getChildren().addAll(lblTitulo, lblSub);
        brandBox.getChildren().addAll(ivLogo, brandTexts);
        sidebar.getChildren().add(brandBox);

        // Botones de navegación
        Map<String, String> iconos = Map.of(
                "Dashboard", "📊 Dashboard",
                "Servidores VPS", "🖥 Servidores VPS",
                "Instituciones", "🏛 Instituciones",
                "Nuevo despliegue", "🚀 Nuevo despliegue",
                "Bases de datos", "🗄 Bases de datos",
                "Actualizaciones", "🔄 Actualizaciones",
                "Copias", "💾 Copias de seguridad",
                "Monitoreo", "🩺 Monitoreo",
                "Historial", "📜 Historial",
                "Configuración", "⚙ Configuración"
        );

        for (String clave : vistas.keySet()) {
            Button btn = new Button(iconos.getOrDefault(clave, clave));
            btn.getStyleClass().add("sidebar-btn");
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(Pos.CENTER_LEFT);
            btn.setOnAction(ev -> navegarA(clave));

            botonesNav.put(clave, btn);
            sidebar.getChildren().add(btn);
        }

        // Espaciador flexible
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // Estado del sistema inferior y acceso a Guía VPSPilot
        VBox footer = new VBox(6);
        footer.getStyleClass().add("sidebar-footer");
        footer.setPadding(new Insets(10, 8, 0, 8));

        Button btnOnboarding = new Button("✨ Guía VPSPilot");
        btnOnboarding.getStyleClass().add("sidebar-btn-tutorial");
        btnOnboarding.setMaxWidth(Double.MAX_VALUE);
        btnOnboarding.setOnAction(ev -> servicios.mainApp().mostrarOnboarding());

        Label lblMotor = new Label("• SQLite Local Activo");
        lblMotor.getStyleClass().add("sidebar-footer-text");

        Label lblSsh = new Label("• Motor SSHJ Listo");
        lblSsh.getStyleClass().add("sidebar-footer-text");

        footer.getChildren().addAll(btnOnboarding, lblMotor, lblSsh);
        sidebar.getChildren().add(footer);

        return sidebar;
    }

    public void actualizarLogo(String tema) {
        if (ivLogo == null) return;
        try {
            boolean esOscuro = AppSettings.TEMA_OSCURO.equalsIgnoreCase(tema);
            String logoRes = esOscuro ? "/ui/logo_icon_darktheme.png" : "/ui/logo_icon.png";
            var stream = getClass().getResourceAsStream(logoRes);
            if (stream == null) stream = getClass().getResourceAsStream("/ui/logo.png");
            if (stream != null) {
                ivLogo.setImage(new Image(stream, 36, 36, true, true));
            }
        } catch (Exception ignored) {
        }
    }

    public void navegarA(String nombreVista) {
        Vista v = vistas.get(nombreVista);
        if (v == null) return;

        // Actualizar estado activo en botones
        for (Map.Entry<String, Button> entry : botonesNav.entrySet()) {
            if (entry.getKey().equals(nombreVista)) {
                entry.getValue().getStyleClass().add("sidebar-btn-activo");
            } else {
                entry.getValue().getStyleClass().remove("sidebar-btn-activo");
            }
        }

        setCenter(v.raiz());
        v.alMostrar();
    }

    public void seleccionarDespliegue(Institution inst) {
        navegarA("Nuevo despliegue");
        vistaDespliegue.preseleccionar(inst);
    }
}

