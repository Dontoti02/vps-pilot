package com.bolsadeploy.domain;

/** Referencia a un commit del repositorio de GitHub. */
public record CommitInfo(String sha, String mensaje, String autor, String fecha) {

    /** SHA corto de 10 caracteres. */
    public String shaCorto() {
        return sha == null ? "" : sha.substring(0, Math.min(10, sha.length()));
    }
}
