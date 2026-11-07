package com.bolsadeploy.infrastructure.ssh;

import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.util.List;

/**
 * Verificador de host keys con política explícita:
 * <ul>
 *   <li>Clave conocida y coincidente → se acepta.</li>
 *   <li>Host desconocido → se pide confirmación con la huella SHA256.</li>
 *   <li>Clave CAMBIADA → se pide confirmación con advertencia fuerte; si el
 *       usuario rechaza, la conexión se cancela (nunca en silencio).</li>
 * </ul>
 * Los rechazos quedan registrados en {@link #getUltimaRechazo()} para que la
 * capa de conexión muestre la causa real en español.
 */
public class StrictHostKeyVerifier implements HostKeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(StrictHostKeyVerifier.class);

    private final HostKeyStore store;
    private final HostKeyPrompt prompt;
    private final boolean verificar;

    /** Última clave pública vista del servidor (para huella post-conexión). */
    private volatile PublicKey ultimaClave;
    /** Motivo del último rechazo (null si no hubo). */
    private volatile String ultimoRechazo;

    public StrictHostKeyVerifier(HostKeyStore store, HostKeyPrompt prompt, boolean verificar) {
        this.store = store;
        this.prompt = prompt;
        this.verificar = verificar;
    }

    public PublicKey getUltimaClave() {
        return ultimaClave;
    }

    public String getUltimoRechazo() {
        return ultimoRechazo;
    }

    @Override
    public List<String> findExistingAlgorithms(String hostname, int port) {
        return List.of();
    }

    @Override
    public boolean verify(String hostname, int port, PublicKey key) {
        this.ultimaClave = key;
        String tipo = SshKeyCodec.algoritmo(key);
        String huella = SshKeyCodec.fingerprint(key);
        String blob = SshKeyCodec.blobBase64(key);
        HostKeyStore.Entrada existente = store.obtener(hostname, port);

        if (existente != null) {
            if (existente.tipo().equals(tipo) && existente.base64().equals(blob)) {
                return true; // clave conocida y correcta
            }
            // CAMBIO de clave: advertencia explícita al usuario
            log.warn("Host key CAMBIADA para {}:{} ({} -> {})", hostname, port, existente.tipo(), tipo);
            if (!verificar || prompt == null || prompt.decidir(hostname, port, tipo, huella, true)) {
                store.guardar(hostname, port, tipo, blob);
                return true;
            }
            ultimoRechazo = "HOST_KEY_MODIFICADA: la huella del servidor " + hostname
                    + " cambió y fue rechazada por el usuario (" + huella + "). Verifica la identidad"
                    + " del servidor antes de continuar.";
            return false;
        }

        // Host desconocido
        if (!verificar || prompt == null || prompt.decidir(hostname, port, tipo, huella, false)) {
            store.guardar(hostname, port, tipo, blob);
            log.info("Host key de {}:{} aceptada ({}, {})", hostname, port, tipo, huella);
            return true;
        }
        ultimoRechazo = "HOST_KEY_DESCONOCIDA: el usuario no aceptó la huella " + huella
                + " del servidor " + hostname + ".";
        return false;
    }
}
