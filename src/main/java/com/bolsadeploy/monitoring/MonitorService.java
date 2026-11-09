package com.bolsadeploy.monitoring;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.domain.CheckLevel;
import com.bolsadeploy.domain.DiagnosticCheck;
import com.bolsadeploy.domain.ServerDiagnostic;
import com.bolsadeploy.infrastructure.ssh.CommandResult;

import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Monitoreo bajo demanda (FASE G).
 *
 * <ul>
 *   <li>{@link #diagnosticar(DeploymentContext)} — recorre el VPS por SSH y
 *       devuelve un {@link ServerDiagnostic} con hallazgos reales (servicios,
 *       disco, memoria, sitio, worker, cron, SSL, logs, respaldos).</li>
 *   <li>{@link #comprobarHttp(String, String)} — comprueba el sitio desde la
 *       máquina local: código HTTP, latencia, ancla de contenido y días de
 *       vigencia del certificado TLS.</li>
 * </ul>
 *
 * Todo check dice qué midió y con qué valor; nada se da por bueno sin leerlo.
 */
public class MonitorService {

    // ------------------------------------------------------------------
    // Diagnóstico por SSH
    // ------------------------------------------------------------------

    public ServerDiagnostic diagnosticar(DeploymentContext ctx) {
        List<DiagnosticCheck> checks = new ArrayList<>();
        try {
            // ------------------------------------------------- Recursos
            String carga = ctx.ejecutar("awk '{print $1,$2,$3}' /proc/loadavg 2>/dev/null", 30)
                    .primeraLinea().trim();
            checks.add(DiagnosticCheck.ok("Carga (1/5/15 min)", carga.isBlank() ? "no disponible" : carga));

            long memUsada = aEntero(ctx.ejecutar(
                    "free -m | awk '/^Mem:/{print $3}'", 30).primeraLinea());
            long memTotal = aEntero(ctx.ejecutar(
                    "free -m | awk '/^Mem:/{print $2}'", 30).primeraLinea());
            checks.add(memTotal > 0 && memUsada * 100 / memTotal > 90
                    ? DiagnosticCheck.error("Memoria", memUsada + "/" + memTotal + " MB usados (>90%)")
                    : DiagnosticCheck.ok("Memoria", memUsada + "/" + memTotal + " MB usados"));

            String disco = ctx.ejecutar("df -h / | awk 'NR==2{print $5\" usado, \"$4\" libres\"}'", 30)
                    .primeraLinea().trim();
            long pctDisco = aEntero(disco);
            checks.add(pctDisco >= 95
                    ? DiagnosticCheck.error("Disco /", disco + " — riesgo de agotamiento")
                    : pctDisco >= 85
                    ? DiagnosticCheck.warn("Disco /", disco)
                    : DiagnosticCheck.ok("Disco /", disco));

            // -------------------------------------------------- Servicios
            checks.addAll(estadoServicios(ctx));

            // ------------------------------------------------- Nginx/FPM
            CommandResult nginxT = ctx.ejecutar("nginx -t 2>&1", 60);
            checks.add(nginxT.exito()
                    ? DiagnosticCheck.ok("Configuración Nginx", "nginx -t correcto")
                    : DiagnosticCheck.error("Configuración Nginx", recortar(nginxT.salida())));

            CommandResult fpmSocket = ctx.ejecutar("ls /run/php/*.sock 2>/dev/null | head -1", 30);
            checks.add(fpmSocket.salida().isBlank()
                    ? DiagnosticCheck.error("Socket PHP-FPM", "no hay ningún socket en /run/php")
                    : DiagnosticCheck.ok("Socket PHP-FPM", fpmSocket.salida().trim()));

            CommandResult mysqlPing = ctx.ejecutar(
                    ctx.sudo("mysqladmin ping 2>/dev/null || mysqladmin --defaults-file=/dev/null ping 2>/dev/null || true"),
                    60);
            checks.add(mysqlPing.salida().contains("alive")
                    ? DiagnosticCheck.ok("MySQL", "responde ping")
                    : DiagnosticCheck.warn("MySQL", "no responde a mysqladmin ping"));

            // ------------------------------------------------------ Sitio
            String dominio = ctx.institucion.dominioPrincipal();
            CommandResult http = ctx.ejecutar(
                    "curl -s -m 15 -H 'Host: " + dominio + "' -o /tmp/vpspilot-mon.html "
                            + "-w '%{http_code}' http://127.0.0.1/ 2>/dev/null; echo; "
                            + "grep -cE 'Whoops|SQLSTATE|Fatal error' /tmp/vpspilot-mon.html 2>/dev/null; "
                            + "wc -c < /tmp/vpspilot-mon.html 2>/dev/null; "
                            + "rm -f /tmp/vpspilot-mon.html", 60);
            String[] partes = http.salida().trim().split("\\s+");
            String codigo = partes.length > 0 ? partes[0] : "";
            long rotos = partes.length > 1 ? aEntero(partes[1]) : 0;
            long bytes = partes.length > 2 ? aEntero(partes[2]) : 0;
            int st = (int) aEntero(codigo);
            if (st >= 500 || st == 0) {
                checks.add(DiagnosticCheck.error("Sitio " + dominio,
                        "HTTP " + (codigo.isBlank() ? "sin respuesta" : codigo) + " en http://127.0.0.1/"));
            } else if (rotos > 0) {
                checks.add(DiagnosticCheck.error("Sitio " + dominio,
                        "responde HTTP " + codigo + " pero muestra errores críticos en el HTML"));
            } else {
                checks.add(DiagnosticCheck.ok("Sitio " + dominio, "HTTP " + codigo + " (" + bytes + " bytes)"));
            }

            // ----------------------------------------------------- Worker
            CommandResult worker = ctx.ejecutar(
                    "supervisorctl status 2>/dev/null | grep 'bolsa-" + ctx.institucion.getSlug() + "'", 60);
            checks.add(worker.salida().contains("RUNNING")
                    ? DiagnosticCheck.ok("Worker de colas", recortar(worker.salida()))
                    : worker.salida().isBlank()
                    ? DiagnosticCheck.warn("Worker de colas", "no hay programa supervisor para esta institución")
                    : DiagnosticCheck.error("Worker de colas", recortar(worker.salida())));

            // ------------------------------------------------------- Cron
            CommandResult cron = ctx.ejecutar(
                    ctx.sudo("crontab -u www-data -l 2>/dev/null | grep -cF 'bolsa-"
                            + ctx.institucion.getSlug() + "'"), 60);
            checks.add(aEntero(cron.primeraLinea()) > 0
                    ? DiagnosticCheck.ok("Cron (schedule:run)", "instalado")
                    : DiagnosticCheck.warn("Cron (schedule:run)", "no instalado: los recordatorios no se enviarán"));

            // -------------------------------------------------------- SSL
            String cert = "/etc/letsencrypt/live/" + dominio + "/fullchain.pem";
            CommandResult hayCert = ctx.ejecutar("test -f " + cert + " && echo SI", 30);
            if ("SI".equals(hayCert.primeraLinea().trim())) {
                CommandResult fin = ctx.ejecutar(
                        "openssl x509 -enddate -noout -in " + cert + " 2>/dev/null", 60);
                String fecha = fin.salida().replace("notAfter=", "").trim();
                try {
                    Instant hasta = Instant.parse(fecha.contains("GMT")
                            ? fecha.replace("GMT", "Z") : fecha);
                    long dias = ChronoUnit.DAYS.between(Instant.now(), hasta);
                    checks.add(dias <= 14
                            ? DiagnosticCheck.error("Certificado SSL", "vence en " + dias + " día(s): " + fecha)
                            : dias <= 30
                            ? DiagnosticCheck.warn("Certificado SSL", "vence en " + dias + " días")
                            : DiagnosticCheck.ok("Certificado SSL", "vence en " + dias + " días"));
                } catch (Exception e) {
                    checks.add(DiagnosticCheck.warn("Certificado SSL",
                            "no se pudo leer la fecha de caducidad (" + fecha + ")"));
                }
                CommandResult timer = ctx.ejecutar(
                        "systemctl is-active certbot.timer 2>/dev/null", 30);
                checks.add("active".equals(timer.primeraLinea().trim())
                        ? DiagnosticCheck.ok("Renovación SSL", "certbot.timer activo")
                        : DiagnosticCheck.warn("Renovación SSL",
                        "certbot.timer no está activo; el certificado puede expirar"));
            } else {
                checks.add(DiagnosticCheck.warn("Certificado SSL",
                        "no emitido para " + dominio + " (sitio en HTTP)"));
            }

            // ------------------------------------------------------- Logs
            CommandResult errores = ctx.ejecutar(
                    "tail -n 300 " + ctx.rutaShared() + "/storage/logs/laravel.log 2>/dev/null "
                            + "| grep -cE '\\[(CRITICAL|ERROR)\\]' || true", 60);
            long n = aEntero(errores.primeraLinea());
            if (n > 0) {
                CommandResult muestra = ctx.ejecutar(
                        "tail -n 300 " + ctx.rutaShared() + "/storage/logs/laravel.log 2>/dev/null "
                                + "| grep -E '\\[(CRITICAL|ERROR)\\]' | tail -2", 60);
                checks.add(DiagnosticCheck.warn("Log de Laravel",
                        n + " error(es) recientes: " + recortar(muestra.salida())));
            } else {
                checks.add(DiagnosticCheck.ok("Log de Laravel", "sin CRITICAL/ERROR recientes"));
            }

            // ---------------------------------------------------- Respaldos
            CommandResult ultimo = ctx.ejecutar(
                    "ls -t " + ctx.rutaBackups() + "/*.{sql.gz,tar.gz} 2>/dev/null | head -1", 60);
            if (ultimo.salida().isBlank()) {
                checks.add(DiagnosticCheck.warn("Respaldos", "no hay respaldos en " + ctx.rutaBackups()));
            } else {
                CommandResult dias = ctx.ejecutar(
                        "echo $(( ( $(date +%s) - $(stat -c %Y '" + ultimo.salida().trim()
                                + "') ) / 86400 ))", 60);
                long edad = aEntero(dias.primeraLinea());
                checks.add(edad > 7
                        ? DiagnosticCheck.error("Respaldos", "el más reciente tiene " + edad + " días")
                        : edad > 2
                        ? DiagnosticCheck.warn("Respaldos", "el más reciente tiene " + edad + " días")
                        : DiagnosticCheck.ok("Respaldos", "reciente (" + edad + " día(s)): "
                        + ultimo.salida().trim().substring(Math.max(0, ultimo.salida().trim().lastIndexOf('/')))));
            }

            // ------------------------------------------------- Release activa
            CommandResult actual = ctx.ejecutar(
                    "readlink -f " + ctx.rutaReleaseActual() + " 2>/dev/null", 30);
            checks.add(actual.salida().isBlank() || !actual.salida().contains("/releases/")
                    ? DiagnosticCheck.error("Release activa", "current no resuelve: " + recortar(actual.salida()))
                    : DiagnosticCheck.ok("Release activa", actual.salida().trim()));

            CommandResult debug = ctx.ejecutar(
                    "grep -E '^APP_DEBUG=' " + ctx.rutaShared() + "/.env 2>/dev/null || true", 30);
            checks.add(debug.salida().contains("APP_DEBUG=false")
                    ? DiagnosticCheck.ok("Modo producción", "APP_DEBUG=false")
                    : DiagnosticCheck.error("Modo producción",
                    "APP_DEBUG no es false: " + recortar(debug.salida())));

            return new ServerDiagnostic(true, "", checks);

        } catch (StageException e) {
            checks.add(DiagnosticCheck.error("SSH", e.getMessage()));
            return new ServerDiagnostic(false, e.getMessage(), checks);
        } catch (Exception e) {
            checks.add(DiagnosticCheck.error("Diagnóstico", e.getMessage()));
            return new ServerDiagnostic(false, e.getMessage(), checks);
        }
    }

    /** Estado real de los servicios systemd de la pila. */
    public List<DiagnosticCheck> estadoServicios(DeploymentContext ctx) {
        List<DiagnosticCheck> salida = new ArrayList<>();
        String[] servicios = {"nginx", ctx.servicioMysql(),
                "php" + ctx.phpObjetivo() + "-fpm", "supervisor"};
        try {
            for (String s : servicios) {
                CommandResult r = ctx.ejecutar("systemctl is-active " + s + " 2>/dev/null", 30);
                String estado = r.primeraLinea().trim();
                if ("active".equals(estado)) {
                    salida.add(DiagnosticCheck.ok("Servicio " + s, "active"));
                } else if (estado.isBlank()) {
                    salida.add(DiagnosticCheck.sinEvaluar("Servicio " + s, "no instalado"));
                } else {
                    salida.add(DiagnosticCheck.error("Servicio " + s, estado));
                }
            }
        } catch (StageException e) {
            salida.add(DiagnosticCheck.error("Servicios", "no se pudo consultar: " + e.getMessage()));
        }
        return salida;
    }

    // ------------------------------------------------------------------
    // Comprobación HTTP desde la máquina local
    // ------------------------------------------------------------------

    /** Resultado de una comprobación HTTP/TLS real. */
    public record HttpCheck(String url, int codigo, long ms, boolean contenidoOk,
                            String detalle, int diasCertificado) {

        public boolean ok() {
            return codigo == 200 && contenidoOk;
        }
    }

    /**
     * @param url        URL completa (http o https)
     * @param anclaEsperada texto que debe aparecer en el cuerpo (null = solo código)
     */
    public HttpCheck comprobarHttp(String url, String anclaEsperada) {
        long inicio = System.currentTimeMillis();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(20_000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "BolsaDeploy-Manager/1.0");
            int codigo = con.getResponseCode();
            StringBuilder cuerpo = new StringBuilder();
            try (var in = codigo >= 400 ? con.getErrorStream() : con.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    int leidos = 0;
                    while (leidos < 300_000 && (n = in.read(buf)) > 0) {
                        cuerpo.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                        leidos += n;
                    }
                }
            }
            long ms = System.currentTimeMillis() - inicio;

            boolean anclaOk = anclaEsperada == null || anclaEsperada.isBlank()
                    || cuerpo.toString().contains(anclaEsperada);
            boolean roto = cuerpo.toString().contains("Whoops")
                    || cuerpo.toString().contains("SQLSTATE")
                    || cuerpo.toString().contains("Fatal error");

            int dias = -1;
            String detalle;
            if (url.startsWith("https://")) {
                dias = diasCertificado(url);
                detalle = "HTTP " + codigo + " en " + ms + " ms"
                        + (dias >= 0 ? ", certificado vence en " + dias + " día(s)" : "")
                        + (roto ? " — ERRORES de aplicación en el HTML" : "")
                        + (anclaOk ? "" : " — falta el contenido esperado");
            } else {
                detalle = "HTTP " + codigo + " en " + ms + " ms"
                        + (roto ? " — ERRORES de aplicación en el HTML" : "")
                        + (anclaOk ? "" : " — falta el contenido esperado");
            }
            return new HttpCheck(url, codigo, ms, anclaOk && !roto, detalle, dias);
        } catch (Exception e) {
            return new HttpCheck(url, -1, System.currentTimeMillis() - inicio, false,
                    "No se pudo contactar: " + e.getMessage(), -1);
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /** Días restantes del certificado TLS del servidor (-1 si no aplica). */
    public int diasCertificado(String url) {
        HttpsURLConnection con = null;
        try {
            con = (HttpsURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.connect();
            var certs = con.getServerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate x) {
                Instant fin = x.getNotAfter().toInstant();
                return (int) ChronoUnit.DAYS.between(Instant.now(), fin);
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
        return -1;
    }

    /** Zona horaria informativa para la UI. */
    public static String zonaLocal() {
        return ZoneId.systemDefault().getId();
    }

    public static Duration timeoutRazonable() {
        return Duration.ofSeconds(20);
    }

    // ------------------------------------------------------------------

    private static long aEntero(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        String limpio = s.replaceAll("[^0-9]", "");
        if (limpio.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(limpio);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String recortar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }
}
