package com.bolsadeploy.updates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Actualizaciones masivas con límite de concurrencia (FASE E).
 *
 * Cada institución se actualiza en su propia tarea; nunca se ejecutan más
 * {@code concurrenciaActualizaciones} a la vez (por defecto 2) para no saturar
 * la red ni el propio VPS. Un fallo en una institución NO detiene las demás:
 * cada resultado se reporta por separado con su motivo real.
 */
public class MassUpdateRunner {

    private static final Logger log = LoggerFactory.getLogger(MassUpdateRunner.class);

    /** Trabajo a realizar sobre una institución (proporciona su contexto). */
    @FunctionalInterface
    public interface Trabajo {
        String ejecutar() throws Exception;
    }

    /** Resultado por institución. */
    public record Resultado(long institucionId, String nombre, boolean exito, String mensaje) {
    }

    /**
     * Ejecuta todas las tareas respetando el límite de concurrencia.
     *
     * @param concurrencia máximo de tareas simultáneas (mínimo 1)
     * @param alTerminar   callback invocado en el mismo hilo llamante en cuanto
     *                     cada tarea termina (para refrescar la UI)
     */
    public List<Resultado> ejecutar(List<InstitucionTarea> tareas, int concurrencia,
                                    java.util.function.Consumer<Resultado> alTerminar) {
        if (tareas == null || tareas.isEmpty()) {
            return List.of();
        }
        int hilos = Math.max(1, Math.min(concurrencia, tareas.size()));
        ExecutorService pool = Executors.newFixedThreadPool(hilos, r -> {
            Thread t = new Thread(r, "bolsa-actualizacion");
            t.setDaemon(true);
            return t;
        });
        List<Resultado> resultados = new ArrayList<>();
        try {
            List<Future<Resultado>> futuros = new ArrayList<>();
            for (InstitucionTarea it : tareas) {
                futuros.add(pool.submit((Callable<Resultado>) () -> {
                    Resultado r;
                    try {
                        String mensaje = it.trabajo().ejecutar();
                        r = new Resultado(it.institucionId(), it.nombre(), true,
                                mensaje == null ? "Actualizada" : mensaje);
                    } catch (Exception e) {
                        log.warn("Actualización fallida para {}: {}", it.nombre(), e.getMessage());
                        r = new Resultado(it.institucionId(), it.nombre(), false,
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                    if (alTerminar != null) {
                        try {
                            alTerminar.accept(r);
                        } catch (Exception ignored) {
                            // la UI no debe romper el hilo de trabajo
                        }
                    }
                    return r;
                }));
            }
            for (Future<Resultado> f : futuros) {
                try {
                    resultados.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    resultados.add(new Resultado(-1, "(interrumpido)", false,
                            "Operación interrumpida por el usuario."));
                    break;
                } catch (Exception e) {
                    resultados.add(new Resultado(-1, "(error)", false,
                            e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        return resultados;
    }

    /** Tarea de actualización de una institución concreta. */
    public record InstitucionTarea(long institucionId, String nombre, Trabajo trabajo) {
    }
}
