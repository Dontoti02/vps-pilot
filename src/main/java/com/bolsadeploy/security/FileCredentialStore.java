package com.bolsadeploy.security;

import com.sun.jna.platform.win32.Crypt32Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Almacén de credenciales persistente.
 *
 * En Windows protege cada secreto con <b>DPAPI</b> (CryptProtectData), vinculado
 * a la cuenta de usuario actual: otro usuario o copia del archivo no puede
 * descifrar los valores. Fuera de Windows usa AES-256/GCM con clave local
 * (documentado en la lista de limitaciones).
 *
 * Archivo: %APPDATA%\BolsaDeployManager\credenciales.properties
 * Formato por clave: {@code <id>=base64(datos protegidos)}
 */
public class FileCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(FileCredentialStore.class);

    private final Path archivo;
    private final boolean windows;
    private final Object lock = new Object();
    private final SecureRandom random = new SecureRandom();

    public FileCredentialStore(Path archivo) {
        this.archivo = archivo;
        this.windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Ruta por defecto según el sistema operativo. */
    public static FileCredentialStore porDefecto() {
        String appdata = System.getenv("APPDATA");
        Path base = appdata != null && !appdata.isBlank()
                ? Path.of(appdata, "BolsaDeployManager")
                : Path.of(System.getProperty("user.home"), ".bolsadeploy");
        return new FileCredentialStore(base.resolve("credenciales.properties"));
    }

    @Override
    public void guardar(String id, String secreto) {
        if (id == null || id.isBlank() || secreto == null) {
            throw new IllegalArgumentException("id y secreto son obligatorios");
        }
        synchronized (lock) {
            Properties props = cargar();
            props.setProperty(id, Base64.getEncoder().encodeToString(proteger(secreto.getBytes(StandardCharsets.UTF_8))));
            guardar(props);
            log.debug("Credencial '{}' guardada (mecanismo {})", id, mecanismo());
        }
    }

    @Override
    public Optional<String> obtener(String id) {
        synchronized (lock) {
            Properties props = cargar();
            String encoded = props.getProperty(id);
            if (encoded == null) {
                return Optional.empty();
            }
            try {
                byte[] datos = Base64.getDecoder().decode(encoded);
                return Optional.of(new String(descifrar(datos), StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("No se pudo descifrar la credencial '{}': {}", id, e.getMessage());
                return Optional.empty();
            }
        }
    }

    @Override
    public void eliminar(String id) {
        synchronized (lock) {
            Properties props = cargar();
            if (props.remove(id) != null) {
                guardar(props);
            }
        }
    }

    @Override
    public boolean existe(String id) {
        synchronized (lock) {
            return cargar().containsKey(id);
        }
    }

    @Override
    public String mecanismo() {
        return windows ? "DPAPI de Windows (vinculado a tu usuario)" : "AES-256 local (no DPAPI)";
    }

    // ------------------------------------------------------------------
    // Protección de datos
    // ------------------------------------------------------------------

    private byte[] proteger(byte[] claro) {
        if (windows) {
            return Crypt32Util.cryptProtectData(claro);
        }
        return aesEncrypt(claro);
    }

    private byte[] descifrar(byte[] protegido) throws IOException {
        if (windows) {
            return Crypt32Util.cryptUnprotectData(protegido);
        }
        return aesDecrypt(protegido);
    }

    private byte[] aesEncrypt(byte[] claro) {
        try {
            byte[] clave = claveLocal();
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(clave, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] cifrado = cipher.doFinal(claro);
            byte[] salida = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, salida, 0, iv.length);
            System.arraycopy(cifrado, 0, salida, iv.length, cifrado.length);
            return salida;
        } catch (Exception e) {
            throw new IllegalStateException("Error cifrando credencial", e);
        }
    }

    private byte[] aesDecrypt(byte[] datos) throws IOException {
        try {
            byte[] clave = claveLocal();
            byte[] iv = new byte[12];
            System.arraycopy(datos, 0, iv, 0, 12);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(clave, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            return cipher.doFinal(datos, 12, datos.length - 12);
        } catch (Exception e) {
            throw new IOException("Error descifrando credencial", e);
        }
    }

    /** Clave local de 32 bytes guardada en archivo aparte (fallback no-Windows). */
    private byte[] claveLocal() throws IOException {
        Path clavePath = archivo.resolveSibling("clave.local");
        synchronized (lock) {
            if (Files.exists(clavePath)) {
                return Base64.getDecoder().decode(Files.readString(clavePath).trim());
            }
            byte[] clave = new byte[32];
            random.nextBytes(clave);
            Files.createDirectories(clavePath.getParent());
            Files.writeString(clavePath, Base64.getEncoder().encodeToString(clave));
            return clave;
        }
    }

    // ------------------------------------------------------------------
    // Persistencia
    // ------------------------------------------------------------------

    private Properties cargar() {
        Properties props = new Properties();
        synchronized (lock) {
            if (Files.exists(archivo)) {
                try (var in = Files.newInputStream(archivo)) {
                    props.load(in);
                } catch (IOException e) {
                    log.warn("No se pudo leer {}: {}", archivo, e.getMessage());
                }
            }
        }
        return props;
    }

    private void guardar(Properties props) {
        synchronized (lock) {
            try {
                Files.createDirectories(archivo.getParent());
                Path tmp = archivo.resolveSibling(archivo.getFileName() + ".tmp");
                try (var out = Files.newOutputStream(tmp)) {
                    props.store(out, "BolsaDeploy Manager - credenciales cifradas. No editar.");
                }
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo guardar el almacén de credenciales", e);
            }
        }
    }

    /** Copia en memoria de las claves (para pruebas/inspección segura). */
    Map<String, String> claves() {
        Map<String, String> salida = new HashMap<>();
        for (var e : cargar().entrySet()) {
            salida.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return salida;
    }
}
