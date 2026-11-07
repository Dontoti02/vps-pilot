package com.bolsadeploy.security;

import java.util.Optional;

/**
 * Almacén de secretos (contraseñas SSH, claves privadas, contraseñas de BD y
 * SMTP). Los secretos NUNCA se guardan en SQLite en texto plano.
 */
public interface CredentialStore {

    /** Guarda o reemplaza un secreto. */
    void guardar(String id, String secreto);

    /** Obtiene un secreto si existe. */
    Optional<String> obtener(String id);

    /** Elimina un secreto. */
    void eliminar(String id);

    /** true si existe el secreto. */
    boolean existe(String id);

    /** Descripción del mecanismo usado (para Configuración → Seguridad). */
    String mecanismo();
}
