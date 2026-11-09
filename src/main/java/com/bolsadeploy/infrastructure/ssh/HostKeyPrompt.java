package com.bolsadeploy.infrastructure.ssh;

/**
 * Decisión del usuario sobre la huella (host key) de un servidor.
 * La UI implementa esto con un diálogo explícito: nunca se acepta
 * silenciosamente una clave nueva ni un cambio inesperado.
 */
public interface HostKeyPrompt {

    HostKeyPrompt ACEPTAR_TODO = (h, p, a, f, c) -> true;

    /**
     * @param host           host o IP
     * @param puerto         puerto SSH
     * @param algoritmo      tipo de clave (ssh-ed25519, ssh-rsa…)
     * @param fingerprint    huella SHA256 calculada
     * @param claveConocida  true si el host ya tenía una clave distinta (CAMBIO)
     * @return true si el usuario acepta explícitamente
     */
    boolean decidir(String host, int puerto, String algoritmo, String fingerprint, boolean claveConocida);
}
