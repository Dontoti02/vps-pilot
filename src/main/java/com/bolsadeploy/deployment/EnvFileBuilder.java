package com.bolsadeploy.deployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constructor del archivo {@code .env} de Laravel.
 *
 * Parte del contenido de {@code .env.example} (se conservan comentarios y
 * orden) y sobrescribe SOLO las claves indicadas. Nunca se genera un .env
 * desde cero con claves inventadas: se parte del ejemplo oficial del repo.
 *
 * Reglas de formato:
 * <ul>
 *   <li>Valores simples → sin comillas.</li>
 *   <li>Valores con espacios, #, $, comillas o caracteres raros → entre comillas
 *       dobles con escape de {@code \} y {@code "}.</li>
 *   <li>Una clave comentada en el ejemplo (# DB_HOST=...) se descomenta al
 *       fijar su valor.</li>
 * </ul>
 *
 * Clase pura y sin E/S: es unit-testeable.
 */
public final class EnvFileBuilder {

    /** Clave=valor (con comentario opcional previo) en el contenido original. */
    private static Pattern lineaClave(String clave) {
        return Pattern.compile("^(\\s*)#?\\s*" + Pattern.quote(clave) + "\\s*=(.*)$");
    }

    /** Valor seguro para escribirlo sin comillas (Laravel lo lee igual). */
    private static final Pattern VALOR_SIMPLE = Pattern.compile("^[A-Za-z0-9_./:@+,-]*$");

    private final List<String> lineasOriginales;
    private final Map<String, String> valores = new LinkedHashMap<>();

    private EnvFileBuilder(List<String> lineasOriginales) {
        this.lineasOriginales = lineasOriginales;
    }

    /** Analiza un contenido .env existente (ej. .env.example). */
    public static EnvFileBuilder parse(String contenido) {
        List<String> lineas = new ArrayList<>();
        if (contenido != null) {
            for (String l : contenido.split("\n", -1)) {
                lineas.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
            }
            // quitar la línea vacía final que produce split
            if (!lineas.isEmpty() && lineas.get(lineas.size() - 1).isEmpty()) {
                lineas.remove(lineas.size() - 1);
            }
        }
        return new EnvFileBuilder(lineas);
    }

    /** Fija (o sobrescribe) el valor de una clave. */
    public EnvFileBuilder poner(String clave, String valor) {
        if (clave == null || clave.isBlank() || clave.contains("=")) {
            throw new IllegalArgumentException("Clave .env no válida: " + clave);
        }
        if (valor == null) {
            valor = "";
        }
        valores.put(clave.trim(), valor);
        return this;
    }

    /** Fija la clave solo si tiene un valor no vacío. */
    public EnvFileBuilder ponerSiPresente(String clave, String valor) {
        if (valor != null && !valor.isBlank()) {
            poner(clave, valor);
        }
        return this;
    }

    /** Valor fijado en esta sesión (null si no se tocó). */
    public String obtener(String clave) {
        return valores.get(clave);
    }

    /** true si el contenido original ya contiene la clave (aunque sea comentada). */
    public boolean contieneEnEjemplo(String clave) {
        Matcher m = lineaClave(clave).matcher(String.join("\n", lineasOriginales));
        return m.find();
    }

    /**
     * Renderiza el .env final: líneas originales con las claves fijadas
     * reemplazadas (o añadidas al final si no existían en el ejemplo).
     */
    public String aContenido() {
        List<String> salida = new ArrayList<>(lineasOriginales);
        boolean[] usada = new boolean[salida.size()];

        // 1) Reemplazar la primera línea (preferentemente sin comentar) de cada clave
        for (Map.Entry<String, String> e : valores.entrySet()) {
            Pattern p = lineaClave(e.getKey());
            int idxSinComentar = -1;
            int idxComentada = -1;
            for (int i = 0; i < salida.size(); i++) {
                String linea = salida.get(i);
                Matcher m = p.matcher(linea);
                if (m.matches()) {
                    if (linea.trim().startsWith("#")) {
                        if (idxComentada < 0) {
                            idxComentada = i;
                        }
                    } else if (idxSinComentar < 0) {
                        idxSinComentar = i;
                    }
                }
            }
            int idx = idxSinComentar >= 0 ? idxSinComentar : idxComentada;
            if (idx >= 0) {
                salida.set(idx, e.getKey() + "=" + formatearValor(e.getValue()));
                usada[idx] = true;
            }
        }

        // 2) Añadir al final las claves que no estaban en el ejemplo
        for (Map.Entry<String, String> e : valores.entrySet()) {
            boolean encontrada = false;
            Pattern p = lineaClave(e.getKey());
            for (String linea : salida) {
                if (p.matcher(linea).matches()) {
                    encontrada = true;
                    break;
                }
            }
            if (!encontrada) {
                salida.add(e.getKey() + "=" + formatearValor(e.getValue()));
            }
        }

        return String.join("\n", salida) + "\n";
    }

    /** Aplica los valores encadenados sobre otro builder (para composición). */
    public EnvFileBuilder todos(Map<String, String> mapa) {
        mapa.forEach(this::poner);
        return this;
    }

    static String formatearValor(String valor) {
        if (valor == null) {
            return "";
        }
        if (VALOR_SIMPLE.matcher(valor).matches()) {
            return valor;
        }
        return "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
