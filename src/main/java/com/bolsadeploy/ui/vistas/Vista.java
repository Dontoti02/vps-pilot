package com.bolsadeploy.ui.vistas;

import javafx.scene.Node;

/**
 * Interfaz común para todas las vistas principales de la aplicación.
 */
public interface Vista {

    /** Nodo raíz de la vista para incrustar en el panel principal. */
    Node raiz();

    /** Invocado cada vez que el usuario navega a esta vista para refrescar datos. */
    default void alMostrar() {
    }
}

