package com.bolsadeploy.deployment;

/** Una etapa independiente del despliegue (patrón Command + State). */
public interface DeploymentStage {

    /** Identificador estable (también se usa como checkpoint). */
    String id();

    /** Nombre para la consola, p.ej. "[OK] Base de datos creada". */
    String nombre();

    /**
     * Ejecuta la etapa. Debe ser <b>idempotente</b>: re-ejecutarla no debe
     * duplicar servicios, configuraciones ni datos.
     *
     * @throws StageException con el fallo explicado en español
     */
    void ejecutar(DeploymentContext ctx) throws StageException;

    /**
     * true si la etapa puede omitirse en una re-ejecución (ya cumplió su
     * objetivo). Por defecto false; las etapas lo calculan con sus comprobaciones.
     */
    default boolean yaCumplida(DeploymentContext ctx) {
        return false;
    }

    /**
     * Si devuelve un motivo no nulo, la etapa se registra como <b>OMITIDA</b>
     * (no como exitosa) con ese texto: p.ej. "HTTPS no solicitado" o
     * "DNS pendiente". Permite no ejecutar nada sin fingir un éxito.
     */
    default String motivoOmitida(DeploymentContext ctx) {
        return null;
    }
}
