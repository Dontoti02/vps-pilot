package com.bolsadeploy.updates;

import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.CommitInfo;
import com.bolsadeploy.domain.FileChange;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.UpdatePlan;
import com.bolsadeploy.domain.VersionComparison;
import com.bolsadeploy.infrastructure.github.GitHubClient;
import com.bolsadeploy.infrastructure.github.GithubException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Construye el {@link UpdatePlan} de una institución comparando lo instalado
 * contra la rama del repositorio (FASE E).
 *
 * El plan se calcula con datos reales de la API de GitHub:
 * <ul>
 *   <li>commits pendientes (mensajes reales, no fechas),</li>
 *   <li>archivos cambiados con su parche,</li>
 *   <li>migraciones pendientes y su riesgo (nombre + contenido destructivo),</li>
 *   <li>archivos sensibles que exigen atención del usuario (seeders,
 *       dependencias, configuración).</li>
 * </ul>
 * Nunca inventa resultados: si GitHub no responde, lanza {@link GithubException}
 * con el motivo.
 */
public final class UpdatePlanner {

    /** Nombres de migración que por sí solos señalan riesgo alto. */
    private static final Pattern NOMBRE_RIESGOSO = Pattern.compile(
            "(?i)(drop|truncate|delete|remove|wipe|purge|recreate|reset|nuke|renew)");
    /** Archivos cuyo cambio debe mostrarse como sensible. */
    private static final Pattern SENSIBLE = Pattern.compile(
            "(^|/)(\\.env\\.example|composer\\.(json|lock)|package(-lock)?\\.json"
                    + "|vite\\.config\\.|database/seeders/|database/factories/|config/)");

    private UpdatePlanner() {
    }

    /**
     * @param commitInstalado SHA actualmente desplegado (vacío si se desconoce)
     * @throws GithubException si GitHub no puede responder (cuota, red, repo)
     */
    public static UpdatePlan planear(GitHubClient gh, AppSettings ajustes, Institution inst,
                                     String commitInstalado) throws GithubException {
        String repoConfig = inst.repositorioEfectivo(ajustes);
        String repo = normalizarRepo(repoConfig);
        String rama = inst.getRama() != null && !inst.getRama().isBlank()
                ? inst.getRama() : ajustes.getRamaDefault();

        VersionComparison cmp = gh.comparar(repo, rama, commitInstalado);

        List<CommitInfo> commits = new ArrayList<>();
        List<FileChange> archivos = new ArrayList<>();
        if (commitInstalado != null && !commitInstalado.isBlank()
                && !commitInstalado.equalsIgnoreCase(cmp.commitDisponible())) {
            commits = gh.rango(repo, commitInstalado, cmp.commitDisponible());
            archivos = gh.archivosCambiados(repo, commitInstalado, cmp.commitDisponible());
        } else if (commitInstalado == null || commitInstalado.isBlank()) {
            // Sin versión conocida: solo se informa el commit disponible
            commits.add(gh.commit(repo, cmp.commitDisponible()));
        }

        // -------------------------------------------------- Migraciones y riesgo
        List<String> migraciones = new ArrayList<>();
        boolean riesgo = false;
        List<String> motivosRiesgo = new ArrayList<>();
        List<String> sensibles = new ArrayList<>();

        for (FileChange f : archivos) {
            if (f.esMigracion()) {
                migraciones.add(f.ruta().substring(f.ruta().lastIndexOf('/') + 1));
                if (NOMBRE_RIESGOSO.matcher(f.ruta()).find()) {
                    riesgo = true;
                    motivosRiesgo.add("migración con nombre destructivo: " + f.ruta());
                }
                if (f.parcheDestructivo()) {
                    riesgo = true;
                    motivosRiesgo.add("migración con SQL destructivo (DROP/TRUNCATE/DELETE): " + f.ruta());
                }
                if ("removed".equalsIgnoreCase(f.estado())) {
                    riesgo = true;
                    migraciones.remove(migraciones.size() - 1);
                    motivosRiesgo.add("migración eliminada del repo: " + f.ruta());
                }
            }
            if (SENSIBLE.matcher(f.ruta()).find() && !sensibles.contains(f.ruta())) {
                sensibles.add(f.ruta());
            }
        }

        // ------------------------------------------------------- Resumen real
        StringBuilder resumen = new StringBuilder();
        if (!cmp.actualizacionDisponible()) {
            resumen.append("Sin cambios: la instalación ya está en el último commit de '").append(rama).append("'.");
        } else {
            resumen.append(commits.size()).append(" commit(s) pendiente(s), ")
                    .append(archivos.size()).append(" archivo(s) modificado(s).");
            int mostrados = 0;
            for (CommitInfo c : commits) {
                if (mostrados >= 5) {
                    break;
                }
                resumen.append("\n • ").append(c.sha().substring(0, Math.min(8, c.sha().length())))
                        .append(" ").append(c.mensaje());
                mostrados++;
            }
            if (commits.size() > mostrados) {
                resumen.append("\n • … y ").append(commits.size() - mostrados).append(" más.");
            }
        }
        if (!migraciones.isEmpty()) {
            resumen.append("\nMigraciones pendientes: ").append(String.join(", ", migraciones));
        }
        if (!sensibles.isEmpty()) {
            resumen.append("\nArchivos sensibles tocados: ").append(String.join(", ", sensibles));
        }
        if (riesgo) {
            resumen.append("\nRIESGO: ").append(String.join("; ", motivosRiesgo));
        }

        return new UpdatePlan(inst, commitInstalado == null ? "" : commitInstalado,
                cmp.commitDisponible(), migraciones, riesgo, sensibles, resumen.toString());
    }

    /** Acepta "owner/repo" o una URL de GitHub y devuelve "owner/repo". */
    public static String normalizarRepo(String repo) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("El repositorio no está configurado.");
        }
        String r = repo.trim();
        r = r.replaceAll("^https?://(www\\.)?github\\.com/", "");
        r = r.replaceAll("^git@github\\.com:", "");
        r = r.replaceAll("\\.git$", "");
        r = r.replaceAll("/+$", "");
        return r;
    }
}
