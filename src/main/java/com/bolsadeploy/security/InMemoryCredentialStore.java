package com.bolsadeploy.security;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Almacén de credenciales en memoria, para pruebas. */
public class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, String> datos = new ConcurrentHashMap<>();

    @Override
    public void guardar(String id, String secreto) {
        datos.put(id, secreto);
    }

    @Override
    public Optional<String> obtener(String id) {
        return Optional.ofNullable(datos.get(id));
    }

    @Override
    public void eliminar(String id) {
        datos.remove(id);
    }

    @Override
    public boolean existe(String id) {
        return datos.containsKey(id);
    }

    @Override
    public String mecanismo() {
        return "memoria (pruebas)";
    }
}
