package com.bolsadeploy.ui.dialogos;

import com.bolsadeploy.infrastructure.ssh.HostKeyPrompt;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diálogo JavaFX interactivo para validar la autenticidad de la clave del servidor SSH.
 * Soporta invocación tanto desde el hilo JavaFX como desde hilos secundarios de trabajo.
 */
public class HostKeyPromptFx implements HostKeyPrompt {

    private static final Logger log = LoggerFactory.getLogger(HostKeyPromptFx.class);

    @Override
    public boolean decidir(String host, int puerto, String algoritmo, String fingerprint, boolean claveConocida) {
        if (Platform.isFxApplicationThread()) {
            return mostrarDialogo(host, puerto, algoritmo, fingerprint, claveConocida);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean aceptado = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                boolean res = mostrarDialogo(host, puerto, algoritmo, fingerprint, claveConocida);
                aceptado.set(res);
            } catch (Exception e) {
                log.error("Error al mostrar prompt de host key: {}", e.getMessage(), e);
            } finally {
                latch.countDown();
            }
        });

        try {
            boolean completado = latch.await(60, TimeUnit.SECONDS);
            if (!completado) {
                log.warn("Tiempo de espera agotado (60s) para confirmación de host key de {}:{}", host, puerto);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return aceptado.get();
    }

    private boolean mostrarDialogo(String host, int puerto, String algoritmo, String fingerprint, boolean claveConocida) {
        Alert alert;
        if (claveConocida) {
            alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("¡ALERTA DE SEGURIDAD CRÍTICA! - Host Key Modificado");
            alert.setHeaderText("¡LA CLAVE DEL SERVIDOR HA CAMBIADO!");
            alert.setContentText("""
                    El servidor en %s:%d envió una clave pública DIFERENTE a la registrada anteriormente.
                    
                    Algoritmo: %s
                    Huella SHA256: %s
                    
                    Esto podría deberse a una reinstalación del VPS o a un posible ataque de intermediario (Man-in-the-Middle).
                    
                    ¿Deseas aceptar la nueva clave bajo tu propio riesgo y sobrescribir el registro?
                    """.formatted(host, puerto, algoritmo, fingerprint));
        } else {
            alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Verificación de Clave de Host SSH");
            alert.setHeaderText("Nuevo servidor SSH detectado");
            alert.setContentText("""
                    No se tiene registro previo de la clave pública para este servidor.
                    
                    Servidor: %s:%d
                    Algoritmo: %s
                    Huella SHA256: %s
                    
                    ¿Confirmas que esta huella coincide con tu servidor y deseas guardarla en known_hosts?
                    """.formatted(host, puerto, algoritmo, fingerprint));
        }

        alert.getDialogPane().getStyleClass().add("dialogo-moderno");

        ButtonType btnAceptar = new ButtonType(claveConocida ? "Aceptar Riesgo" : "Confiar y Conectar", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancelar = new ButtonType("Cancelar Conexión", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(btnAceptar, btnCancelar);

        var respuesta = alert.showAndWait();
        return respuesta.isPresent() && respuesta.get() == btnAceptar;
    }
}

