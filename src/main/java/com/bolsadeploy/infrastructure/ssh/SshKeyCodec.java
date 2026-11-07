package com.bolsadeploy.infrastructure.ssh;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.EdECPoint;
import java.util.Base64;

/**
 * Codificador de claves públicas al formato OpenSSH (RFC 4716/8032):
 * blob = string(algoritmo) + datos, en base64. Permite guardar known_hosts y
 * calcular huellas SHA256 idénticas a las de `ssh-keygen -lf`.
 */
public final class SshKeyCodec {

    private SshKeyCodec() {
    }

    /** Nombre de algoritmo OpenSSH de la clave. */
    public static String algoritmo(PublicKey key) {
        if (key != null) {
            try {
                KeyType kt = KeyType.fromKey(key);
                if (kt != null && kt != KeyType.UNKNOWN) {
                    return kt.toString();
                }
            } catch (Exception ignored) {
            }
        }
        if (key instanceof RSAPublicKey) {
            return "ssh-rsa";
        }
        if (key instanceof EdECPublicKey) {
            return "ssh-ed25519";
        }
        if (key instanceof java.security.interfaces.DSAPublicKey) {
            return "ssh-dss";
        }
        if (key instanceof ECPublicKey ec) {
            int bits = ec.getParams().getCurve().getField().getFieldSize();
            return switch (bits) {
                case 256 -> "ecdsa-sha2-nistp256";
                case 384 -> "ecdsa-sha2-nistp384";
                case 521 -> "ecdsa-sha2-nistp521";
                default -> throw new IllegalArgumentException("Curva EC no soportada: " + bits + " bits");
            };
        }
        if (key != null && ("EdDSA".equalsIgnoreCase(key.getAlgorithm()) || "Ed25519".equalsIgnoreCase(key.getAlgorithm()))) {
            return "ssh-ed25519";
        }
        throw new IllegalArgumentException("Clave no soportada: " + (key == null ? "null" : key.getClass().getName()));
    }

    /** Blob binario OpenSSH de la clave pública. */
    public static byte[] blob(PublicKey key) {
        if (key != null) {
            try {
                KeyType kt = KeyType.fromKey(key);
                if (kt != null && kt != KeyType.UNKNOWN) {
                    Buffer.PlainBuffer buf = new Buffer.PlainBuffer();
                    kt.putPubKeyIntoBuffer(key, buf);
                    return buf.getCompactData();
                }
            } catch (Exception ignored) {
            }
        }

        String alg = algoritmo(key);
        if (key instanceof RSAPublicKey rsa) {
            return concat(cadena(alg), mpint(rsa.getPublicExponent()), mpint(rsa.getModulus()));
        }
        if (key instanceof EdECPublicKey ed) {
            return concat(cadena(alg), cadena(puntoEd25519(ed.getPoint())));
        }
        // Soporte por reflexión para implementaciones externas de Ed25519 (sshj / eddsa)
        if (key != null && ("ssh-ed25519".equals(alg) || "EdDSA".equalsIgnoreCase(key.getAlgorithm()))) {
            try {
                var m = key.getClass().getMethod("getAbyte");
                byte[] aBytes = (byte[]) m.invoke(key);
                if (aBytes != null) {
                    return concat(cadena("ssh-ed25519"), cadena(aBytes));
                }
            } catch (Exception ignored) {
            }
        }
        if (key instanceof java.security.interfaces.DSAPublicKey dsa) {
            var params = dsa.getParams();
            return concat(cadena(alg), mpint(dsa.getY()), mpint(params.getP()),
                    mpint(params.getQ()), mpint(params.getG()));
        }
        if (key instanceof ECPublicKey ec) {
            int bytes = (ec.getParams().getCurve().getField().getFieldSize() + 7) / 8;
            String curva = alg.substring("ecdsa-".length()); // p.ej. sha2-nistp256
            byte[] punto = concat(fijo(ec.getW().getAffineX(), bytes), fijo(ec.getW().getAffineY(), bytes));
            return concat(cadena(alg), cadena(curva), cadena(punto));
        }
        throw new IllegalArgumentException("Clave no soportada: " + (key == null ? "null" : key.getClass().getName()));
    }

    /** Blob en base64 (formato known_hosts). */
    public static String blobBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(blob(key));
    }

    /** Huella SHA256 formato OpenSSH: SHA256:<base64 sin padding>. */
    public static String fingerprint(PublicKey key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(blob(key));
            return "SHA256:" + Base64.getEncoder().encodeToString(hash).replace("=", "");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la huella", e);
        }
    }

    /** Compara dos claves por su blob OpenSSH. */
    public static boolean mismasClaves(PublicKey a, PublicKey b) {
        try {
            return MessageDigest.isEqual(blob(a), blob(b));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Codificación binaria
    // ------------------------------------------------------------------

    static byte[] cadena(byte[] datos) {
        return concat(longBytes(datos.length), datos);
    }

    static byte[] cadena(String s) {
        return cadena(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** mpint RFC 4716: sin ceros a la izquierda, 0x00 si el bit alto está puesto. */
    static byte[] mpint(BigInteger n) {
        byte[] magnitud = n.toByteArray(); // ya incluye 0x00 si hace falta
        // quitar ceros iniciales redundantes (toByteArray ya lo hace, salvo el 0x00 de signo)
        int inicio = 0;
        while (inicio < magnitud.length - 1 && magnitud[inicio] == 0) {
            inicio++;
        }
        byte[] limpio = java.util.Arrays.copyOfRange(magnitud, inicio, magnitud.length);
        return concat(longBytes(limpio.length), limpio);
    }

    /** Entero big-endian de 4 bytes. */
    static byte[] longBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** Entero fijo de N bytes big-endian con relleno a la izquierda. */
    static byte[] fijo(BigInteger n, int bytes) {
        byte[] src = n.toByteArray();
        byte[] out = new byte[bytes];
        int copia = Math.min(src.length, bytes);
        System.arraycopy(src, src.length - copia, out, bytes - copia, copia);
        return out;
    }

    /**
     * Punto Ed25519 (RFC 8032): 32 bytes little-endian de Y con el signo de X
     * en el bit 255.
     */
    static byte[] puntoEd25519(EdECPoint punto) {
        byte[] salida = new byte[32];
        byte[] y = fijoLE(punto.getY(), 32);
        System.arraycopy(y, 0, salida, 0, 32);
        if (punto.isXOdd()) {
            salida[31] |= (byte) 0x80;
        }
        return salida;
    }

    /** Entero little-endian fijo de N bytes. */
    static byte[] fijoLE(BigInteger n, int bytes) {
        byte[] src = n.toByteArray();
        byte[] out = new byte[bytes];
        // toByteArray es big-endian con signo; invertir magnitud a little-endian
        int inicio = 0;
        while (inicio < src.length - 1 && src[inicio] == 0) {
            inicio++;
        }
        byte[] mag = java.util.Arrays.copyOfRange(src, inicio, src.length);
        for (int i = 0; i < mag.length && i < bytes; i++) {
            out[i] = mag[mag.length - 1 - i];
        }
        return out;
    }

    static byte[] concat(byte[]... partes) {
        int total = 0;
        for (byte[] p : partes) {
            total += p.length;
        }
        byte[] salida = new byte[total];
        int off = 0;
        for (byte[] p : partes) {
            System.arraycopy(p, 0, salida, off, p.length);
            off += p.length;
        }
        return salida;
    }
}
