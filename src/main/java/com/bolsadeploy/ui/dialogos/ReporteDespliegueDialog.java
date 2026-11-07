package com.bolsadeploy.ui.dialogos;

import com.bolsadeploy.domain.DeploymentReport;
import com.bolsadeploy.domain.StageResult;
import com.bolsadeploy.ui.componentes.UiHelper;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import java.awt.Desktop;
import java.net.URI;

/**
 * Diálogo modal para visualizar el resultado final del despliegue y copiar el resumen.
 */
public class ReporteDespliegueDialog extends Dialog<Void> {

    public ReporteDespliegueDialog(DeploymentReport r) {
        setTitle("Reporte Final de Despliegue");
        setHeaderText(null);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().add("dialogo-moderno");
        pane.setPrefSize(750, 600);

        VBox root = new VBox(14);
        root.setPadding(new Insets(16));

        // Cabecera de estado
        HBox cabecera = new HBox(12);
        cabecera.setAlignment(Pos.CENTER_LEFT);

        Label lblEstado = UiHelper.badge(r.isExitoso() ? "DESPLIEGUE EXITOSO" : "DESPLIEGUE CON ERRORES",
                r.isExitoso() ? "exito" : "error");
        lblEstado.setStyle("-fx-font-size: 14px; -fx-padding: 6px 14px;");

        Label lblTitulo = new Label(r.getInstitucion() + " (" + r.getDominio() + ")");
        lblTitulo.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        cabecera.getChildren().addAll(lblEstado, lblTitulo);

        // Ficha técnica resumida
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        grid.getStyleClass().add("card");
        grid.setPadding(new Insets(10));

        grid.add(new Label("Servidor VPS:"), 0, 0);
        grid.add(new Label(r.getServidorNombre() + " (" + r.getServidorIp() + ")"), 1, 0);

        grid.add(new Label("Versión / Commit:"), 0, 1);
        grid.add(new Label(r.getVersionDesplegada() + " (" + r.getCommit() + ")"), 1, 1);

        grid.add(new Label("Servicios:"), 2, 0);
        grid.add(new Label(r.getServicios() == null ? "Nginx, PHP 8.2, MySQL" : r.getServicios()), 3, 0);

        grid.add(new Label("Estado SSL:"), 2, 1);
        grid.add(new Label(r.getEstadoSsl() == null ? "-" : r.getEstadoSsl()), 3, 1);

        grid.add(new Label("URL de Acceso:"), 0, 2);
        Hyperlink link = new Hyperlink(r.getEnlace() != null ? r.getEnlace() : "http://" + r.getDominio());
        link.setOnAction(ev -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(link.getText()));
                }
            } catch (Exception ignored) {
            }
        });
        grid.add(link, 1, 2, 3, 1);

        // Tabla de etapas
        TableView<StageResult> tablaEtapas = new TableView<>();
        tablaEtapas.setPrefHeight(240);

        TableColumn<StageResult, String> colEtapa = new TableColumn<>("Etapa");
        colEtapa.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().etapaId()));
        colEtapa.setPrefWidth(160);

        TableColumn<StageResult, String> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().estado().name()));
        colEstado.setPrefWidth(100);

        TableColumn<StageResult, String> colDuracion = new TableColumn<>("Duración");
        colDuracion.setCellValueFactory(c -> new SimpleStringProperty((c.getValue().duracionMs() / 1000.0) + " s"));
        colDuracion.setPrefWidth(90);

        TableColumn<StageResult, String> colMensaje = new TableColumn<>("Detalle");
        colMensaje.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().mensaje()));
        colMensaje.setPrefWidth(320);

        tablaEtapas.getColumns().addAll(colEtapa, colEstado, colDuracion, colMensaje);
        tablaEtapas.getItems().addAll(r.getEtapas());

        // Botones inferiores
        HBox botones = new HBox(10);
        botones.setAlignment(Pos.CENTER_RIGHT);

        Button btnCopiar = new Button("Copiar Resumen");
        btnCopiar.setOnAction(ev -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== REPORTE DE DESPLIEGUE ===\n");
            sb.append("Institución: ").append(r.getInstitucion()).append("\n");
            sb.append("Dominio: ").append(r.getDominio()).append("\n");
            sb.append("Servidor: ").append(r.getServidorNombre()).append(" (").append(r.getServidorIp()).append(")\n");
            sb.append("Resultado: ").append(r.isExitoso() ? "EXITOSO" : "FALLIDO").append("\n");
            sb.append("Commit: ").append(r.getCommit()).append("\n");
            sb.append("Enlace: ").append(r.getEnlace()).append("\n\n");
            sb.append("--- Etapas ---\n");
            for (StageResult s : r.getEtapas()) {
                sb.append(String.format("- %-20s [%s] %s (%.2f s)\n",
                        s.etapaId(), s.estado(), s.mensaje(), s.duracionMs() / 1000.0));
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(sb.toString());
            Clipboard.getSystemClipboard().setContent(content);
            btnCopiar.setText("¡Copiado!");
        });

        Button btnCerrar = new Button("Cerrar");
        btnCerrar.setOnAction(ev -> close());

        botones.getChildren().addAll(btnCopiar, btnCerrar);

        root.getChildren().addAll(cabecera, grid, new Label("Detalle de las 11 Etapas:"), tablaEtapas, botones);
        pane.setContent(root);

        pane.getButtonTypes().add(ButtonType.CLOSE);
        // Ocultar botón default
        var closeBtn = pane.lookupButton(ButtonType.CLOSE);
        if (closeBtn != null) {
            closeBtn.setVisible(false);
            closeBtn.setManaged(false);
        }
    }
}

