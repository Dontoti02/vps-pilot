package com.bolsadeploy.ui.componentes;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.Optional;

/**
 * Utilidades para construcción de elementos de interfaz y diálogos uniformes en español.
 */
public final class UiHelper {

    private UiHelper() {
    }

    public static VBox cabeceraSeccion(String titulo, String descripcion) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(0, 0, 16, 0));

        Label lblTitulo = new Label(titulo);
        lblTitulo.getStyleClass().add("seccion-titulo");

        Label lblDesc = new Label(descripcion);
        lblDesc.getStyleClass().add("seccion-descripcion");
        lblDesc.setWrapText(true);

        box.getChildren().addAll(lblTitulo, lblDesc);
        return box;
    }

    public static VBox tarjetaMetrica(String titulo, String valor, String subtexto, String colorBorde) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("tarjeta-metrica", "card");
        if (colorBorde != null && !colorBorde.isBlank()) {
            card.setStyle("-fx-border-color: " + colorBorde + "; -fx-border-width: 0 0 0 4px;");
        }

        Label lblTitulo = new Label(titulo.toUpperCase());
        lblTitulo.getStyleClass().add("metrica-titulo");

        Label lblValor = new Label(valor);
        lblValor.getStyleClass().add("metrica-valor");

        Label lblSub = new Label(subtexto);
        lblSub.getStyleClass().add("metrica-subtexto");

        card.getChildren().addAll(lblTitulo, lblValor, lblSub);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    public static Label badge(String texto, String estiloTipo) {
        Label lbl = new Label(texto);
        lbl.getStyleClass().addAll("badge", "badge-" + estiloTipo);
        return lbl;
    }

    public static VBox campoFormulario(String etiqueta, Node control, String ayuda) {
        VBox box = new VBox(4);
        Label lbl = new Label(etiqueta);
        lbl.getStyleClass().add("form-label");
        box.getChildren().addAll(lbl, control);
        if (ayuda != null && !ayuda.isBlank()) {
            Label lblAyuda = new Label(ayuda);
            lblAyuda.getStyleClass().add("form-help");
            lblAyuda.setWrapText(true);
            box.getChildren().add(lblAyuda);
        }
        return box;
    }

    public static void alertaError(String titulo, String mensaje) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(titulo);
        alert.setHeaderText(null);
        alert.setContentText(mensaje);
        alert.getDialogPane().getStyleClass().add("dialogo-moderno");
        alert.showAndWait();
    }

    public static void alertaExito(String titulo, String mensaje) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(titulo);
        alert.setHeaderText("Operación completada con éxito");
        alert.setContentText(mensaje);
        alert.getDialogPane().getStyleClass().add("dialogo-moderno");
        alert.showAndWait();
    }

    public static void alertaInfo(String titulo, String cabecera, String mensaje) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(titulo);
        alert.setHeaderText(cabecera);
        alert.setContentText(mensaje);
        alert.getDialogPane().getStyleClass().add("dialogo-moderno");
        alert.showAndWait();
    }

    public static void alertaAdvertencia(String titulo, String cabecera, String mensaje) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(titulo);
        alert.setHeaderText(cabecera);
        alert.setContentText(mensaje);
        alert.getDialogPane().getStyleClass().add("dialogo-moderno");
        alert.showAndWait();
    }

    public static boolean confirmar(String titulo, String cabecera, String mensaje) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(titulo);
        alert.setHeaderText(cabecera);
        alert.setContentText(mensaje);
        alert.getDialogPane().getStyleClass().add("dialogo-moderno");

        ButtonType btnSi = new ButtonType("Confirmar", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnNo = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(btnSi, btnNo);

        Optional<ButtonType> res = alert.showAndWait();
        return res.isPresent() && res.get() == btnSi;
    }
}

