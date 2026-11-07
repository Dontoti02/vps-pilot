package com.bolsadeploy.infrastructure.github;

/** Error al consultar la API de GitHub, con causa en español. */
public class GithubException extends Exception {

    public GithubException(String mensaje) {
        super(mensaje);
    }

    public GithubException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
