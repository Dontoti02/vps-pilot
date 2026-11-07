package com.bolsadeploy.infrastructure.ssh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Almacén de host keys en formato OpenSSH known_hosts:
 * {@code host keytype base64}. Previene ataques man-in-the-middle: si la clave
 * del host cambia, se solicita confirmación explícita al usuario.
 */
public class HostKeyStore {

    private static final Logger log = LoggerFactory.getLogger(HostKeyStore.class);

    private final Path archivo;
    private final Map<String, Entrada> entradas = new LinkedHashMap<>();
    private final Object lock = new Object();

    public record Entrada(String host, int puerto, String tipo, String base64) {
    }

    public HostKeyStore(Path archivo) {
        this.archivo = archivo;
        cargar();
    }

    private static String clave(String host, int puerto) {
        return puerto == 22 ? host : "[" + host + "]:" + puerto;
    }

    private void cargar() {
        synchronized (lock) {
            entradas.clear();
            if (!Files.exists(archivo)) {
                return;
            }
            try {
                for (String linea : Files.readAllLines(archivo, StandardCharsets.UTF_8)) {
                    String l = linea.trim();
                    if (l.isEmpty() || l.startsWith("#")) {
                        continue;
                    }
                    String[] partes = l.split("\\s+");
                    if (partes.length < 3) {
                        continue;
                    }
                    String hostPart = partes[0];
                    int puerto = 22;
                    String host = hostPart;
                    if (hostPart.startsWith("[")) {
                        int cierre = hostPart.indexOf(']');
                        host = hostPart.substring(1, cierre);
                        int dp = hostPart.indexOf(':', cierre);
                        if (dp > 0) {
                            puerto = Integer.parseInt(hostPart.substring(dp + 1));
                        }
                    }
                    entradas.put(clave(host, puerto),
                            new Entrada(host, puerto, partes[1], partes[2]));
                }
            } catch (IOException e) {
                log.warn("No se pudo leer known_hosts: {}", e.getMessage());
            }
        }
    }

    /** Devuelve la entrada del host si existe. */
    public Entrada obtener(String host, int puerto) {
        synchronized (lock) {
            return entradas.get(clave(host, puerto));
        }
    }

    /** Guarda (o reemplaza) la clave del host en known_hosts. */
    public void guardar(String host, int puerto, String tipo, String base64) {
        synchronized (lock) {
            entradas.put(clave(host, puerto), new Entrada(host, puerto, tipo, base64));
            try {
                Files.createDirectories(archivo.getParent());
                StringBuilder sb = new StringBuilder("# BolsaDeploy Manager known_hosts\n");
                for (Entrada e : entradas.values()) {
                    String h = e.puerto() == 22 ? e.host() : "[" + e.host() + "]:" + e.puerto();
                    sb.append(h).append(' ').append(e.tipo()).append(' ').append(e.base64()).append('\n');
                }
                Path tmp = archivo.resolveSibling(archivo.getFileName() + ".tmp");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo guardar known_hosts", e);
            }
        }
    }

    /** Elimina la entrada de un host (para "olvidar" un servidor). */
    public void eliminar(String host, int puerto) {
        synchronized (lock) {
            if (entradas.remove(clave(host, puerto)) != null) {
                reescribir();
            }
        }
    }

    private void reescribir() {
        try {
            Files.createDirectories(archivo.getParent());
            StringBuilder sb = new StringBuilder("# BolsaDeploy Manager known_hosts\n");
            for (Entrada e : entradas.values()) {
                String h = e.puerto() == 22 ? e.host() : "[" + e.host() + "]:" + e.puerto();
                sb.append(h).append(' ').append(e.tipo()).append(' ').append(e.base64()).append('\n');
            }
            Files.writeString(archivo, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("No se pudo reescribir known_hosts: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Fingerprint y codificación de claves (delegado a SshKeyCodec)
    // ------------------------------------------------------------------
}
