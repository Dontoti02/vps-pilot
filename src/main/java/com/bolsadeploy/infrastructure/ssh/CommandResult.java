package com.bolsadeploy.infrastructure.ssh;

/**
 * Resultado de un comando remoto. {@code salida} combina stdout y stderr para
 * mostrar en la consola; nunca contiene secretos porque los comandos se
 * construyen con plantillas que evitan imprimir credenciales.
 */
public record CommandResult(String comando, int exitCode, String stdout, String stderr) {

    public boolean exito() {
        return exitCode == 0;
    }

    /** stdout + stderr (lo que se muestra en la consola de operaciones). */
    public String salida() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isBlank()) {
            sb.append(stdout);
        }
        if (stderr != null && !stderr.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(stderr);
        }
        return sb.toString();
    }

    /** Primera línea no vacía de stdout (para extraer valores). */
    public String primeraLinea() {
        if (stdout == null) {
            return "";
        }
        for (String linea : stdout.split("\n")) {
            String l = linea.trim();
            if (!l.isEmpty()) {
                return l;
            }
        }
        return "";
    }
}
