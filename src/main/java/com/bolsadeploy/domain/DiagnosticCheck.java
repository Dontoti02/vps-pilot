package com.bolsadeploy.domain;

/** Un hallazgo del diagnóstico de un servidor. */
public record DiagnosticCheck(String nombre, CheckLevel nivel, String detalle) {

    public static DiagnosticCheck ok(String nombre, String detalle) {
        return new DiagnosticCheck(nombre, CheckLevel.CORRECTO, detalle);
    }

    public static DiagnosticCheck warn(String nombre, String detalle) {
        return new DiagnosticCheck(nombre, CheckLevel.ADVERTENCIA, detalle);
    }

    public static DiagnosticCheck error(String nombre, String detalle) {
        return new DiagnosticCheck(nombre, CheckLevel.ERROR, detalle);
    }

    public static DiagnosticCheck sinEvaluar(String nombre, String detalle) {
        return new DiagnosticCheck(nombre, CheckLevel.NO_EVALUADO, detalle);
    }
}
