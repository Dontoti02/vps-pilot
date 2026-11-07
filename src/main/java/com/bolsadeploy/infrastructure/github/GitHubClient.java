package com.bolsadeploy.infrastructure.github;

import com.bolsadeploy.domain.CommitInfo;
import com.bolsadeploy.domain.VersionComparison;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente de la API pública de GitHub para consultar versiones del repositorio
 * de la bolsa laboral. Sin token permite ~60 consultas/hora (suficiente para
 * comprobaciones manuales); con token en Configuración → GitHub se amplía.
 */
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String API_DEFECTO = "https://api.github.com/";
    private static final String USER_AGENT = "BolsaDeploy-Manager/1.0";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiBase;
    private volatile String token = "";

    public GitHubClient() {
        this(API_DEFECTO);
    }

    /** Solo para pruebas: apunta la API a un servidor local (HttpServer del JDK). */
    public GitHubClient(String baseUrl) {
        this.apiBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.json = new ObjectMapper();
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    // ------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------

    /** Últimos commits de una rama. */
    public java.util.List<CommitInfo> ultimosCommits(String repo, String rama, int cantidad) throws GithubException {
        JsonNode arr = get("repos/" + repo + "/commits?sha=" + codificar(rama) + "&per_page=" + cantidad);
        java.util.List<CommitInfo> salida = new java.util.ArrayList<>();
        for (JsonNode n : arr) {
            salida.add(aCommit(n));
        }
        return salida;
    }

    /** Información de un commit específico (sha, tag o rama). */
    public CommitInfo commit(String repo, String ref) throws GithubException {
        JsonNode n = get("repos/" + repo + "/commits/" + codificar(ref));
        return aCommit(n);
    }

    /** Tags (versiones) del repositorio, más recientes primero. */
    public java.util.List<String> tags(String repo) throws GithubException {
        JsonNode arr = get("repos/" + repo + "/tags?per_page=30");
        java.util.List<String> salida = new java.util.ArrayList<>();
        for (JsonNode n : arr) {
            salida.add(n.path("name").asText(""));
        }
        return salida;
    }

    /**
     * Compara la versión instalada contra la disponible: cuántos commits está
     * adelante la disponible y si hay actualización real (no por fecha).
     */
    public VersionComparison comparar(String repo, String rama, String commitInstalado) throws GithubException {
        String disponible = commit(repo, rama).sha();
        if (commitInstalado == null || commitInstalado.isBlank()) {
            return new VersionComparison("", disponible, true, rama, -1);
        }
        if (commitInstalado.equalsIgnoreCase(disponible)) {
            return VersionComparison.igual(commitInstalado, rama);
        }
        int adelante = -1;
        try {
            JsonNode cmp = get("repos/" + repo + "/compare/"
                    + codificar(commitInstalado) + "..." + codificar(disponible));
            adelante = cmp.path("ahead_by").asInt(-1);
        } catch (GithubException e) {
            log.debug("Comparación no disponible ({}); se usará igualdad de SHA", e.getMessage());
        }
        boolean hay = adelante != 0; // 0 = misma versión; >0 = adelante; -1 = no determinable (distinto SHA)
        return new VersionComparison(commitInstalado, disponible, hay, rama, adelante);
    }

    /** Commits entre dos referencias (para mostrar cambios pendientes). */
    public java.util.List<CommitInfo> rango(String repo, String desde, String hasta) throws GithubException {
        JsonNode n = get("repos/" + repo + "/compare/" + codificar(desde) + "..." + codificar(hasta));
        java.util.List<CommitInfo> salida = new java.util.ArrayList<>();
        for (JsonNode c : n.path("commits")) {
            salida.add(aCommit(c));
        }
        return salida;
    }

    /**
     * Archivos cambiados entre dos referencias con su parche: base para
     * detectar migraciones pendientes y riesgo destructivo real.
     */
    public java.util.List<com.bolsadeploy.domain.FileChange> archivosCambiados(String repo, String desde, String hasta)
            throws GithubException {
        JsonNode n = get("repos/" + repo + "/compare/" + codificar(desde) + "..." + codificar(hasta));
        java.util.List<com.bolsadeploy.domain.FileChange> salida = new java.util.ArrayList<>();
        for (JsonNode f : n.path("files")) {
            String ruta = f.path("filename").asText("");
            if (ruta.isBlank()) {
                continue;
            }
            salida.add(new com.bolsadeploy.domain.FileChange(
                    ruta,
                    f.path("patch").asText(""),
                    f.path("status").asText("modified")));
        }
        return salida;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private JsonNode get(String ruta) throws GithubException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + ruta))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        if (!token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 403 || code == 429) {
                String resto = resp.headers().firstValue("x-ratelimit-remaining").orElse("");
                if ("0".equals(resto)) {
                    throw new GithubException("Se agotó la cuota de consultas de GitHub (60/h sin token)."
                            + " Configure un token en Configuración → GitHub o intente más tarde.");
                }
                throw new GithubException("GitHub denegó la consulta (HTTP " + code + ").");
            }
            if (code == 404) {
                throw new GithubException("Repositorio o referencia no encontrado (HTTP 404): " + ruta);
            }
            if (code >= 400) {
                throw new GithubException("Error de la API de GitHub (HTTP " + code + ").");
            }
            return json.readTree(resp.body());
        } catch (GithubException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GithubException("Consulta a GitHub interrumpida.", e);
        } catch (Exception e) {
            throw new GithubException("No se pudo contactar con GitHub: " + e.getMessage(), e);
        }
    }

    private static CommitInfo aCommit(JsonNode n) {
        JsonNode commit = n.path("commit");
        String sha = n.path("sha").asText("");
        String mensaje = commit.path("message").asText("");
        if (mensaje.contains("\n")) {
            mensaje = mensaje.substring(0, mensaje.indexOf('\n'));
        }
        String autor = commit.path("author").path("name").asText("");
        String fecha = commit.path("author").path("date").asText("");
        return new CommitInfo(sha, mensaje, autor, fecha);
    }

    private static String codificar(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
