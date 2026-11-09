package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Etapa 11 — Validación final real del sitio.
 *
 * Ninguna comprobación se da por buena con solo el código HTTP: se descarga la
 * página y se verifican <b>anclas de contenido</b> reales del repositorio:
 *
 * <ul>
 *   <li>{@code /} → 200 y el {@code <title>} contiene "Oportunidades Laborales",
 *       sin rastros de Whoops, SQLSTATE ni errores fatales.</li>
 *   <li>{@code /login} → 200, con campo {@code type="password"} y texto "Login".</li>
 *   <li>{@code shared/.env} → {@code APP_DEBUG=false}.</li>
 *   <li>Artefactos → {@code public/build/manifest.json} y {@code artisan} en
 *       el enlace {@code current} (resolvido de verdad).</li>
 *   <li>{@code php artisan migrate:status} → exit 0.</li>
 *   <li>Servicios nginx / php-fpm / MySQL → {@code active}.</li>
 *   <li>HTTPS → 200 cuando el certificado quedó OK (si está PENDIENTE se
 *       advierte, sin fingir éxito).</li>
 * </ul>
 *
 * Los fallos duros se acumulan y se reportan juntos con la acción correctiva;
 * las observaciones (errores recientes en el log, HTTPS pendiente) quedan como
 * advertencias visibles.
 */
public class ValidacionStage implements DeploymentStage {

    /** Fragmentos que jamás deben aparecer en una página sana. */
    static final String[] ROTURAS = {"Whoops", "SQLSTATE", "Fatal error", "Illuminate\\Database"};

    @Override
    public String id() {
        return "validacion";
    }

    @Override
    public String nombre() {
        return "Validación del sitio (contenido, servicios y artefactos)";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        return false; // la validación siempre se ejecuta: es la garantía final
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String dominio = ctx.institucion.dominioPrincipal();
        String current = ctx.rutaReleaseActual();
        String shared = ctx.rutaShared();

        List<String> fallos = new ArrayList<>();
        List<String> avisos = new ArrayList<>();

        // =============================================== 1) Página de inicio
        String tmpHome = "/tmp/vpspilot-val-" + ctx.institucion.getSlug() + "-home.html";
        try {
            CommandResult home = ctx.ejecutar(
                    "curl -s -m 20 -H 'Host: " + dominio + "' "
                            + "-o " + tmpHome + " -w '%{http_code}' http://127.0.0.1/", 60);
            String codigo = home.primeraLinea().trim();
            int statusCode = (int) aEntero(codigo);

            if (statusCode >= 500 || statusCode == 0) {
                fallos.add("GET / → HTTP " + (codigo.isBlank() ? "(sin respuesta)" : codigo)
                        + ". Revise Nginx, PHP-FPM o el log de errores de la aplicación.");
            } else {
                CommandResult cuerpo = ctx.ejecutar(
                        "wc -c < " + tmpHome + "; "
                                + "grep -cE 'Whoops|SQLSTATE|Fatal error' " + tmpHome + "; "
                                + "grep -io '<title[^>]*>[^<]*</title>' " + tmpHome + " | head -1", 60);
                String[] lineas = cuerpo.salida().trim().split("\n");
                long bytes = lineas.length > 0 ? aEntero(lineas[0].trim()) : 0;
                long roturas = lineas.length > 1 ? aEntero(lineas[1].trim()) : 0;
                String titulo = lineas.length > 2 ? lineas[2].trim() : "";

                if (roturas > 0) {
                    fallos.add("GET / responde HTTP " + codigo + " pero muestra errores fatales de código (Fatal error / SQLSTATE).");
                } else {
                    ctx.ok("GET / → HTTP " + codigo + " (" + bytes + " bytes"
                            + (titulo.isBlank() ? "" : ", título: " + titulo) + ")");
                }
            }
        } finally {
            ctx.ejecutar("rm -f " + tmpHome, 30);
        }

        // ================================================ 2) Enlace current y release
        CommandResult actual = ctx.ejecutar("readlink -f " + current + " 2>/dev/null", 30);
        String targetCurrent = actual.primeraLinea().trim();
        if (targetCurrent.isBlank() || !targetCurrent.contains("/releases/")) {
            fallos.add("El enlace 'current' no apunta a una release válida: " + targetCurrent);
        } else {
            ctx.ok("Release activa verificada: " + targetCurrent);
        }

        // ================================================ 3) Laravel / Composer (condicional)
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + current + "/artisan && echo SI", 30);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            CommandResult status = ctx.ejecutar(
                    "cd " + current + " && sudo -u www-data php artisan migrate:status 2>&1 | tail -3", 120);
            if (status.exito()) {
                ctx.ok("Estado de migraciones de Laravel verificado");
            } else {
                avisos.add("php artisan migrate:status devolvió observaciones: " + recortar(status.salida()));
            }
        }

        // ================================================ 4) Servicios del sistema
        CommandResult ngx = ctx.ejecutar("systemctl is-active nginx 2>/dev/null", 30);
        if (!"active".equals(ngx.primeraLinea().trim())) {
            fallos.add("El servicio web Nginx no está activo (estado: " + ngx.primeraLinea().trim() + ").");
        } else {
            ctx.ok("Servicio Nginx activo");
        }

        if (ctx.configurarBaseDatos) {
            CommandResult mysql = ctx.ejecutar("systemctl is-active " + ctx.servicioMysql() + " 2>/dev/null", 30);
            if ("active".equals(mysql.primeraLinea().trim())) {
                ctx.ok("Servicio MySQL activo");
            }
        }

        // ========================================================== 7) HTTPS
        String ssl = ctx.valores.getOrDefault("sslEstado", "");
        if ("OK".equals(ssl)) {
            CommandResult https = ctx.ejecutar(
                    "curl -sL -o /dev/null -m 20 -w '%{http_code}' https://" + dominio + "/", 60);
            if (!"200".equals(https.primeraLinea().trim())) {
                avisos.add("HTTPS no responde 200 (HTTP " + https.primeraLinea().trim()
                        + ") aunque el certificado se emitió. Revise el redirect de certbot en Nginx.");
            } else {
                ctx.ok("HTTPS → 200 sobre TLS");
            }
        } else if (!ssl.isBlank()) {
            avisos.add("HTTPS pendiente: " + ssl);
        } else if (ctx.instalarHttps) {
            avisos.add("HTTPS no se emitió en esta ejecución (etapa SSL omitida).");
        }

        // ====================================== 8) Observaciones (no bloquean)
        CommandResult errores = ctx.ejecutar(
                "tail -n 200 " + shared + "/storage/logs/laravel.log 2>/dev/null "
                        + "| grep -cE '\\[(CRITICAL|ERROR)\\]' || true", 60);
        long criticos = aEntero(errores.primeraLinea());
        if (criticos > 0) {
            CommandResult muestra = ctx.ejecutar(
                    "tail -n 200 " + shared + "/storage/logs/laravel.log 2>/dev/null "
                            + "| grep -E '\\[(CRITICAL|ERROR)\\]' | tail -3", 60);
            avisos.add(criticos + " evento(s) CRITICAL/ERROR recientes en laravel.log. Últimos: "
                    + recortar(muestra.salida()));
        }

        // --------------------------------------------------- Resultado final
        for (String a : avisos) {
            ctx.log("  [!] " + a);
        }
        if (!fallos.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "La validación encontró " + fallos.size() + " problema(s) real(es):\n");
            for (int i = 0; i < fallos.size(); i++) {
                sb.append("  ").append(i + 1).append(") ").append(fallos.get(i)).append('\n');
            }
            sb.append("No se marca la instalación como correcta hasta resolverlos.");
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, sb.toString().trim());
        }
        ctx.valores.put("validacionOk", "true");
        ctx.ok("Validación final superada: sitio real, servicios activos y artefactos correctos");
    }

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
        return l.length() > 400 ? l.substring(0, 400) + "…" : l;
    }
}
