package com.bolsadeploy.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validación y escape de parámetros que viajan a comandos remotos.
 * Regla central: ninguna entrada de usuario se ejecuta como instrucción de
 * shell sin validar contra estos patrones.
 */
public final class CommandValidator {

    // Slug de institución: minúsculas, dígitos y guiones (p.ej. "iestp-demo")
    public static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

    // Nombres SQL: letras/dígitos/_ (sin guiones para evitar ambigüedad)
    public static final Pattern SQL_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{1,63}$");

    // Dominio DNS
    public static final Pattern DOMINIO = Pattern.compile(
            "^(?=.{4,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$");

    // Rutas absolutas de Linux dentro de la zona de instalación
    public static final Pattern RUTA_ABS = Pattern.compile("^/[a-zA-Z0-9._/-]{1,500}$");

    // Ramas/tags de git (sin caracteres especiales de shell)
    public static final Pattern GIT_REF = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}$");

    // SHA de commit (40 hex)
    public static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    // Correo electrónico simple
    public static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    // Hostname o IPv4/IPv6 razonable
    public static final Pattern HOST = Pattern.compile(
            "^[a-zA-Z0-9.\\-:]{1,253}$");

    private CommandValidator() {
    }

    public static boolean esSlug(String v) {
        return v != null && SLUG.matcher(v).matches();
    }

    public static boolean esNombreSql(String v) {
        return v != null && SQL_NAME.matcher(v).matches();
    }

    public static boolean esDominio(String v) {
        return v != null && DOMINIO.matcher(v).matches();
    }

    public static boolean esRutaAbsoluta(String v) {
        return v != null && RUTA_ABS.matcher(v).matches();
    }

    public static boolean esGitRef(String v) {
        return v != null && GIT_REF.matcher(v).matches();
    }

    public static boolean esSha(String v) {
        return v != null && SHA.matcher(v).matches();
    }

    public static boolean esEmail(String v) {
        return v != null && EMAIL.matcher(v).matches();
    }

    public static boolean esHost(String v) {
        return v != null && HOST.matcher(v).matches();
    }

    /**
     * Convierte un nombre de institución en slug seguro para rutas, nombres de
     * base de datos y servicios. Reemplaza todo lo no permitido por guiones.
     */
    public static String aSlug(String nombre) {
        if (nombre == null) {
            return "";
        }
        String s = nombre.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "")
                .replaceAll("-{2,}", "-");
        if (s.length() < 2) {
            s = "inst" + Math.abs(nombre.hashCode());
        }
        if (s.length() > 60) {
            s = s.substring(0, 60);
        }
        return s;
    }

    /** Escape de argumento para comillas simples en shell: ' -> '\'' */
    public static String escaparShell(String valor) {
        if (valor == null) {
            return "''";
        }
        return "'" + valor.replace("'", "'\\''") + "'";
    }

    /**
     * Escape para valor SQL dentro de comillas simples (MySQL acepta '' como
     * escape de comilla; tambien se neutraliza la barra invertida final).
     */
    public static String escaparSql(String valor) {
        if (valor == null) {
            return "";
        }
        String s = valor.replace("\\", "\\\\").replace("'", "''");
        // Neutraliza secuencias de escape de MySQL (\n, \0...) si llegan crudas
        return s;
    }

    /** Valida una referencia git y la devuelve limpia; lanza si es inválida. */
    public static String exigirGitRef(String ref) {
        if (!esGitRef(ref)) {
            throw new IllegalArgumentException("Referencia git no válida: " + ref);
        }
        return ref;
    }

    /** Valida un slug; lanza si es inválido. */
    public static String exigirSlug(String slug) {
        if (!esSlug(slug)) {
            throw new IllegalArgumentException("Slug no válido: " + slug);
        }
        return slug;
    }

    /** Valida un nombre SQL; lanza si es inválido. */
    public static String exigirNombreSql(String nombre) {
        if (!esNombreSql(nombre)) {
            throw new IllegalArgumentException("Nombre SQL no válido: " + nombre);
        }
        return nombre;
    }

    /** Valida un dominio; lanza si es inválido. */
    public static String exigirDominio(String dominio) {
        if (!esDominio(dominio)) {
            throw new IllegalArgumentException("Dominio no válido: " + dominio);
        }
        return dominio;
    }
}
