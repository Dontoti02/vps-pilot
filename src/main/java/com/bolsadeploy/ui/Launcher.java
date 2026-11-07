package com.bolsadeploy.ui;

import javax.swing.JOptionPane;

/**
 * Lanzador independiente que no hereda de Application.
 * Necesario para permitir que JavaFX se inicialice correctamente desde el classpath en Windows.
 */
public class Launcher {

    public static void main(String[] args) {
        try {
            BolsaDeployApp.main(args);
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                JOptionPane.showMessageDialog(null,
                        "Error al iniciar BolsaDeploy Manager:\n" + t.getMessage() + "\n" + t.getClass().getName(),
                        "Error Crítico",
                        JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
            }
        }
    }
}
