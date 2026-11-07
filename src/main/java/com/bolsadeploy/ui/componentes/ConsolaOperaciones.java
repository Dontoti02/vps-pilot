package com.bolsadeploy.ui.componentes;

import com.bolsadeploy.deployment.DeploymentListener;
import com.bolsadeploy.domain.OperationState;
import com.bolsadeploy.domain.StageResult;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Componente visual de consola para ejecución de despliegues, actualizaciones y respaldos.
 * Hilo-seguro: todas las actualizaciones se dirigen a Platform.runLater().
 */
public class ConsolaOperaciones extends VBox {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ProgressBar progressBar;
    private final Label lblEstado;
    private final Label lblPorcentaje;
    private final FlowPane panelEtapas;
    private final TextArea areaTexto;

    private final Map<String, Label> chipsEtapas = new HashMap<>();

    public ConsolaOperaciones() {
        setSpacing(10);
        setPadding(new Insets(12));
        getStyleClass().addAll("consola-operaciones", "card");

        // Barra superior con progreso
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        lblEstado = new Label("En espera...");
        lblEstado.getStyleClass().add("consola-estado-texto");
        HBox.setHgrow(lblEstado, Priority.ALWAYS);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(240);
        progressBar.setProgress(0);

        lblPorcentaje = new Label("0%");
        lblPorcentaje.getStyleClass().add("consola-porcentaje");

        topBar.getChildren().addAll(lblEstado, progressBar, lblPorcentaje);

        // Panel de chips de etapas
        panelEtapas = new FlowPane(6, 6);
        panelEtapas.setAlignment(Pos.CENTER_LEFT);
        panelEtapas.setPadding(new Insets(4, 0, 4, 0));

        // Área de texto tipo terminal
        areaTexto = new TextArea();
        areaTexto.setEditable(false);
        areaTexto.setWrapText(true);
        areaTexto.setPrefRowCount(14);
        areaTexto.getStyleClass().add("terminal-area");
        VBox.setVgrow(areaTexto, Priority.ALWAYS);

        getChildren().addAll(topBar, panelEtapas, areaTexto);
    }

    public void limpiar() {
        Platform.runLater(() -> {
            areaTexto.clear();
            panelEtapas.getChildren().clear();
            chipsEtapas.clear();
            progressBar.setProgress(0);
            lblEstado.setText("Listo para ejecutar.");
            lblPorcentaje.setText("0%");
        });
    }

    public void log(String linea) {
        if (linea == null) return;
        Platform.runLater(() -> {
            String timestamp = LocalTime.now().format(HORA);
            areaTexto.appendText("[" + timestamp + "] " + linea + "\n");
            areaTexto.selectPositionCaret(areaTexto.getLength());
            areaTexto.deselect();
        });
    }

    public void progreso(int porcentaje, String mensaje) {
        Platform.runLater(() -> {
            double p = Math.max(0, Math.min(100, porcentaje)) / 100.0;
            progressBar.setProgress(p);
            lblPorcentaje.setText(porcentaje + "%");
            if (mensaje != null && !mensaje.isBlank()) {
                lblEstado.setText(mensaje);
            }
        });
    }

    public void etapaIniciada(String id, String nombre) {
        Platform.runLater(() -> {
            lblEstado.setText("Ejecutando: " + nombre);
            Label chip = chipsEtapas.get(id);
            if (chip == null) {
                chip = new Label(nombre);
                chipsEtapas.put(id, chip);
                panelEtapas.getChildren().add(chip);
            }
            chip.getStyleClass().setAll("badge", "badge-en-curso");
        });
        log(">>> [ETAPA INICIADA] " + nombre);
    }

    public void etapaFinalizada(StageResult res) {
        Platform.runLater(() -> {
            Label chip = chipsEtapas.get(res.etapaId());
            if (chip != null) {
                if (res.estado() == OperationState.EXITOSA) {
                    chip.getStyleClass().setAll("badge", "badge-exito");
                } else if (res.estado() == OperationState.FALLIDA) {
                    chip.getStyleClass().setAll("badge", "badge-error");
                } else if (res.estado() == OperationState.OMITIDA) {
                    chip.getStyleClass().setAll("badge", "badge-omitida");
                }
            }
        });
        String duracion = res.duracionMs() > 0 ? " (" + (res.duracionMs() / 1000.0) + "s)" : "";
        log("<<< [ETAPA FINALIZADA: " + res.estado() + "] " + res.mensaje() + duracion);
        if (res.salidaTecnica() != null && !res.salidaTecnica().isBlank()) {
            log("    Salida técnica: " + res.salidaTecnica().replace("\n", " | "));
        }
    }

    /** Crea un listener para conectar directamente al servicio de despliegue. */
    public DeploymentListener asDeploymentListener() {
        return new DeploymentListener() {
            @Override
            public void onEtapaIniciada(String id, String nombre) {
                ConsolaOperaciones.this.etapaIniciada(id, nombre);
            }

            @Override
            public void onLog(String linea) {
                ConsolaOperaciones.this.log(linea);
            }

            @Override
            public void onEtapaFinalizada(StageResult resultado) {
                ConsolaOperaciones.this.etapaFinalizada(resultado);
            }

            @Override
            public void onProgreso(int porcentaje) {
                ConsolaOperaciones.this.progreso(porcentaje, null);
            }
        };
    }
}

