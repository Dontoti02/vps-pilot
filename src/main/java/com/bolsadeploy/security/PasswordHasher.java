package com.bolsadeploy.security;

import org.mindrot.jbcrypt.BCrypt;

/** Utilidad de hash bcrypt para el administrador inicial (coste 12, como Laravel). */
public final class PasswordHasher {

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    public static boolean verificar(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(password, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Genera una contraseña aleatoria segura para uso administrativo. */
    public static String generarAleatoria(int longitud) {
        final String alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(longitud);
        for (int i = 0; i < longitud; i++) {
            sb.append(alfabeto.charAt(rnd.nextInt(alfabeto.length())));
        }
        return sb.toString();
    }
}
