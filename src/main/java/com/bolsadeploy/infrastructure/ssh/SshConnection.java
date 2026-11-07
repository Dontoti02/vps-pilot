package com.bolsadeploy.infrastructure.ssh;

import com.bolsadeploy.domain.AuthType;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sesión SSH activa. Ejecuta comandos remotos, sube y baja archivos por SFTP.
 * Todas las operaciones son bloqueantes: se ejecutan FUERA del hilo de UI.
 */
public class SshConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshConnection.class);

    private final SSHClient client;
    private final String host;
    private final int puerto;
    private final String usuario;
    private final StrictHostKeyVerifier verifier;

    private SshConnection(SSHClient client, String host, int puerto, String usuario,
                          StrictHostKeyVerifier verifier) {
        this.client = client;
        this.host = host;
        this.puerto = puerto;
        this.usuario = usuario;
        this.verifier = verifier;
    }

    /** Parámetros de conexión. */
    public record Params(String host, int puerto, String usuario, AuthType authType,
                         String secreto, int timeoutSeg, boolean verificarHostKeys) {
    }

    /**
     * Establece la conexión y autentica. Lanza {@link SshException} con la causa
     * real (auth rechazada, puerto cerrado, timeout, host key…).
     */
    public static SshConnection conectar(Params p, HostKeyStore hostKeys,
                                         HostKeyPrompt prompt) throws SshException {
        SSHClient client = new SSHClient();
        int timeoutMs = Math.max(5, p.timeoutSeg()) * 1000;
        StrictHostKeyVerifier verifier = new StrictHostKeyVerifier(hostKeys, prompt, p.verificarHostKeys());
        try {
            client.setConnectTimeout(timeoutMs);
            client.setTimeout(timeoutMs);
            client.addHostKeyVerifier(verifier);

            try {
                client.connect(p.host(), p.puerto());
            } catch (SocketTimeoutException e) {
                throw new SshException(SshException.Codigo.TIMEOUT,
                        "Tiempo de espera agotado al conectar con " + p.host() + ":" + p.puerto()
                                + " (revisa red, firewall o que el servicio SSH esté activo).", e);
            } catch (ConnectException e) {
                throw new SshException(SshException.Codigo.PUERTO_CERRADO,
                        "Conexión rechazada en " + p.host() + ":" + p.puerto()
                                + " (puerto cerrado o servicio SSH caído).", e);
            } catch (UnknownHostException | NoRouteToHostException e) {
                throw new SshException(SshException.Codigo.DNS_NO_RESUELTA,
                        "No se pudo resolver la dirección " + p.host() + " (error DNS o red).", e);
            } catch (TransportException e) {
                SshException hk = porRechazoHostKey(verifier, e);
                if (hk != null) {
                    throw hk;
                }
                throw new SshException(SshException.Codigo.SESION_CORTADA,
                        "El transporte SSH falló durante la conexión: " + mensajeLimpio(e), e);
            } catch (IOException e) {
                SshException hk = porRechazoHostKey(verifier, e);
                if (hk != null) {
                    throw hk;
                }
                if (e.getCause() instanceof GeneralSecurityException gse) {
                    throw mapearHostKey(gse);
                }
                if (e.getCause() instanceof SocketTimeoutException) {
                    throw new SshException(SshException.Codigo.TIMEOUT,
                            "Tiempo de espera agotado al conectar con " + p.host() + ":" + p.puerto() + ".", e);
                }
                throw new SshException(SshException.Codigo.DESCONOCIDO,
                        "No se pudo conectar: " + mensajeLimpio(e), e);
            } catch (RuntimeException e) {
                // sshj puede propagar SecurityException sin envolver
                SshException hk = porRechazoHostKey(verifier, e);
                if (hk != null) {
                    throw hk;
                }
                throw new SshException(SshException.Codigo.DESCONOCIDO,
                        "No se pudo conectar: " + mensajeLimpio(e), e);
            }

            // Autenticación
            try {
                if (p.authType() == AuthType.KEY) {
                    var keyProvider = client.loadKeys(p.secreto());
                    client.authPublickey(p.usuario(), keyProvider);
                } else {
                    try {
                        client.authPassword(p.usuario(), p.secreto());
                    } catch (UserAuthException uae) {
                        // Fallback a keyboard-interactive si el servidor sólo admite PAM/keyboard-interactive
                        client.auth(p.usuario(), new net.schmizz.sshj.userauth.method.AuthKeyboardInteractive(
                                new net.schmizz.sshj.userauth.method.ChallengeResponseProvider() {
                                    @Override
                                    public java.util.List<String> getSubmethods() {
                                        return java.util.List.of();
                                    }

                                    @Override
                                    @SuppressWarnings("rawtypes")
                                    public void init(net.schmizz.sshj.userauth.password.Resource resource, String name, String instruction) {
                                    }

                                    @Override
                                    public char[] getResponse(String prompt, boolean echo) {
                                        return p.secreto() != null ? p.secreto().toCharArray() : new char[0];
                                    }

                                    @Override
                                    public boolean shouldRetry() {
                                        return false;
                                    }
                                }));
                    }
                }
            } catch (UserAuthException e) {
                client.disconnect();
                throw new SshException(SshException.Codigo.AUTENTICACION_RECHAZADA,
                        "Autenticación rechazada para el usuario '" + p.usuario() + "' en " + p.host()
                                + " (contraseña o clave privada incorrectas).", e);
            } catch (IOException e) {
                client.disconnect();
                if (e.getCause() instanceof GeneralSecurityException gse) {
                    throw mapearHostKey(gse);
                }
                throw new SshException(SshException.Codigo.DESCONOCIDO,
                        "Falló la autenticación: " + mensajeLimpio(e), e);
            }

            // Tras conectar: sin timeout de lectura (los comandos largos lo manejan con join)
            client.setTimeout(0);
            log.info("SSH conectado a {}:{} como {}", p.host(), p.puerto(), p.usuario());
            return new SshConnection(client, p.host(), p.puerto(), p.usuario(), verifier);

        } catch (SshException e) {
            throw e;
        } catch (IOException e) {
            throw new SshException(SshException.Codigo.DESCONOCIDO, "Error SSH: " + mensajeLimpio(e), e);
        }
    }

    /** Si el verificador rechazó la host key, convierte eso en SshException. */
    private static SshException porRechazoHostKey(StrictHostKeyVerifier verifier, Exception e) {
        String r = verifier.getUltimoRechazo();
        if (r == null) {
            return null;
        }
        SshException.Codigo codigo = r.startsWith("HOST_KEY_MODIFICADA")
                ? SshException.Codigo.HOST_KEY_MODIFICADA
                : SshException.Codigo.HOST_KEY_DESCONOCIDA;
        return new SshException(codigo, r, e);
    }

    private static SshException mapearHostKey(GeneralSecurityException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("HOST_KEY_MODIFICADA")) {
            return new SshException(SshException.Codigo.HOST_KEY_MODIFICADA,
                    "La huella (host key) del servidor cambió y fue rechazada. Verifica la identidad"
                            + " del servidor antes de continuar: " + msg, e);
        }
        if (msg.contains("HOST_KEY_DESCONOCIDA")) {
            return new SshException(SshException.Codigo.HOST_KEY_DESCONOCIDA,
                    "El usuario rechazó la huella del servidor desconocido: " + msg, e);
        }
        return new SshException(SshException.Codigo.DESCONOCIDO,
                "Error de seguridad SSH: " + msg, e);
    }

    /**
     * Ejecuta un comando y captura stdout/stderr/exit con tiempo límite.
     * Si se supera, se cierra la sesión (el canal remoto muere) y se lanza TIMEOUT.
     */
    public CommandResult ejecutar(String comando, int timeoutSeg) throws SshException {
        Session session = null;
        try {
            session = client.startSession();
            Session.Command cmd = session.exec(comando);
            CompletableFuture<String> out = leerAsync(cmd.getInputStream());
            CompletableFuture<String> err = leerAsync(cmd.getErrorStream());
            try {
                cmd.join(Math.max(1, timeoutSeg), TimeUnit.SECONDS);
            } catch (ConnectionException e) {
                // join agotado u otra falla de conexión
                try {
                    cmd.close();
                } catch (Exception ignored) {
                }
                if (mensajeLimpio(e).toLowerCase().contains("timed out")) {
                    throw new SshException(SshException.Codigo.TIMEOUT,
                            "El comando superó el tiempo límite de " + timeoutSeg + " s.", e);
                }
                throw new SshException(SshException.Codigo.SESION_CORTADA,
                        "La sesión SSH se interrumpió: " + mensajeLimpio(e), e);
            }
            String stdout = out.get(60, TimeUnit.SECONDS);
            String stderr = err.get(30, TimeUnit.SECONDS);
            Integer exit = cmd.getExitStatus();
            try {
                cmd.close();
            } catch (Exception ignored) {
            }
            return new CommandResult(comando, exit == null ? -1 : exit, stdout, stderr);
        } catch (SshException e) {
            throw e;
        } catch (Exception e) {
            throw mapearFalloSesion(e);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Sube un archivo local al servidor (SFTP). */
    public void subir(Path local, String rutaRemota) throws SshException {
        try (SFTPClient sftp = client.newSFTPClient()) {
            sftp.put(local.toAbsolutePath().toString(), rutaRemota);
        } catch (IOException e) {
            throw new SshException(SshException.Codigo.SESION_CORTADA,
                    "No se pudo subir el archivo a " + rutaRemota + ": " + mensajeLimpio(e), e);
        }
    }

    /** Descarga un archivo del servidor a una ruta local (SFTP). */
    public void descargar(String rutaRemota, Path local) throws SshException {
        try {
            Files.createDirectories(local.getParent());
        } catch (IOException e) {
            throw new SshException(SshException.Codigo.DESCONOCIDO,
                    "No se pudo crear el directorio local: " + e.getMessage(), e);
        }
        try (SFTPClient sftp = client.newSFTPClient()) {
            sftp.get(rutaRemota, local.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new SshException(SshException.Codigo.SESION_CORTADA,
                    "No se pudo descargar " + rutaRemota + ": " + mensajeLimpio(e), e);
        }
    }

    /** Huella SHA256 de la clave pública del servidor (capturada al verificar). */
    public String fingerprintServidor() {
        try {
            java.security.PublicKey clave = verifier.getUltimaClave();
            return clave == null ? "(no disponible)" : SshKeyCodec.fingerprint(clave);
        } catch (Exception e) {
            return "(no disponible)";
        }
    }

    public String getHost() {
        return host;
    }

    public int getPuerto() {
        return puerto;
    }

    public String getUsuario() {
        return usuario;
    }

    public boolean conectado() {
        return client.isConnected();
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------

    private static CompletableFuture<String> leerAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream is = in) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static SshException mapearFalloSesion(Exception e) {
        Throwable causa = e;
        while (causa != null) {
            if (causa instanceof SocketTimeoutException) {
                return new SshException(SshException.Codigo.TIMEOUT,
                        "El comando superó el tiempo límite o la conexión quedó sin respuesta.", e);
            }
            if (causa instanceof ConnectException) {
                return new SshException(SshException.Codigo.PUERTO_CERRADO,
                        "La conexión fue rechazada.", e);
            }
            if (causa instanceof UserAuthException) {
                return new SshException(SshException.Codigo.AUTENTICACION_RECHAZADA,
                        "Autenticación rechazada durante la sesión.", e);
            }
            if (causa instanceof ConnectionException || causa instanceof TransportException) {
                return new SshException(SshException.Codigo.SESION_CORTADA,
                        "La sesión SSH se interrumpió: " + mensajeLimpio(e), e);
            }
            causa = causa.getCause();
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new SshException(SshException.Codigo.SESION_CORTADA, "Operación cancelada.", e);
        }
        return new SshException(SshException.Codigo.DESCONOCIDO,
                "Fallo SSH: " + mensajeLimpio(e), e);
    }

    private static String mensajeLimpio(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
