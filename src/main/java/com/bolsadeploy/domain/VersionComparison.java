package com.bolsadeploy.domain;

/** Comparación de versión instalada vs. disponible. */
public record VersionComparison(String commitInstalado, String commitDisponible,
                                boolean actualizacionDisponible, String rama,
                                int commitsAdelante) {

    public static VersionComparison igual(String sha, String rama) {
        return new VersionComparison(sha, sha, false, rama, 0);
    }
}
