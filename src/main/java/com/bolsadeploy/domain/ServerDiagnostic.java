package com.bolsadeploy.domain;

import java.util.List;

/**
 * Resultado de un diagnóstico completo de VPS. {@code sshOk} indica si la
 * conexión SSH real fue exitosa; los demás checks solo se evalúan si sshOk.
 */
public record ServerDiagnostic(boolean sshOk, String errorSsh, List<DiagnosticCheck> checks) {

    public long contar(CheckLevel nivel) {
        return checks.stream().filter(c -> c.nivel() == nivel).count();
    }

    /** true si ningún check tiene nivel ERROR. */
    public boolean sinErrores() {
        return checks.stream().noneMatch(c -> c.nivel() == CheckLevel.ERROR);
    }

    /** Resumen de una línea para la UI. */
    public String resumen() {
        if (!sshOk) {
            return "SSH fallido: " + (errorSsh == null ? "error desconocido" : errorSsh);
        }
        return "OK:" + contar(CheckLevel.CORRECTO)
                + " WARN:" + contar(CheckLevel.ADVERTENCIA)
                + " ERR:" + contar(CheckLevel.ERROR)
                + " SIN:" + contar(CheckLevel.NO_EVALUADO);
    }
}
