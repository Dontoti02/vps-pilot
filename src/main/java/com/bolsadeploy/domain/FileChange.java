package com.bolsadeploy.domain;

/**
 * Archivo modificado entre dos commits, con su parche (diff) cuando GitHub lo
 * entrega. Se usa para detectar riesgo real de actualización (migraciones
 * destructivas) en lugar de adivinar por nombre.
 */
public record FileChange(String ruta, String patch, String estado) {

    /** true si el archivo es una migración de Laravel. */
    public boolean esMigracion() {
        return ruta != null && ruta.startsWith("database/migrations/") && ruta.endsWith(".php");
    }

    /** true si el parche contiene SQL/operaciones destructivas. */
    public boolean parcheDestructivo() {
        if (patch == null || patch.isBlank()) {
            return false;
        }
        return MIGRACION_DESTRUCTIVA.matcher(patch).find();
    }

    private static final java.util.regex.Pattern MIGRACION_DESTRUCTIVA =
            java.util.regex.Pattern.compile(
                    "(?i)(DROP\\s+TABLE|DROP\\s+COLUMN|dropColumn\\s*\\(|dropForeign\\s*\\("
                            + "|TRUNCATE\\s+|DELETE\\s+FROM|dropAllTables|Schema::drop\\s*\\()");
}
