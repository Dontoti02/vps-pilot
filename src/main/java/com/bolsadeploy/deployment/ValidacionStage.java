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

    /** Ancla de la landing: <title>{{ ... }} - Oportunidades Laborales</title>. */
    static final String ANCLA_HOME = "Oportunidades Laborales";
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
        String tmpHome = "/tmp/bolsa-val-" + ctx.institucion.getSlug() + "-home.html";
        try {
            CommandResult home = ctx.ejecutar(
                    "curl -s -m 20 -H 'Host: " + dominio + "' "
                            + "-o " + tmpHome + " -w '%{http_code}' http://127.0.0.1/", 60);
            String codigo = home.primeraLinea().trim();
            if (!"200".equals(codigo)) {
                fallos.add("GET / → HTTP " + (codigo.isBlank() ? "(sin respuesta)" : codigo)
                        + ". Revise el vhost, PHP-FPM y los permisos de " + current + "/public.");
            } else {
                CommandResult cuerpo = ctx.ejecutar(
                        "wc -c < " + tmpHome + "; grep -c '" + ANCLA_HOME + "' " + tmpHome
                                + "; grep -cE 'Whoops|SQLSTATE|Fatal error' " + tmpHome, 60);
                String[] datos = cuerpo.salida().trim().split("\\s+");
                long bytes = datos.length > 0 ? aEntero(datos[0]) : 0;
                long anclas = datos.length > 1 ? aEntero(datos[1]) : 0;
                long roturas = datos.length > 2 ? aEntero(datos[2]) : 0;
                if (bytes < 500) {
                    fallos.add("GET / devuelve solo " + bytes + " bytes: la página no se renderizó "
                            + "(revise storage/, bootstrap/cache y el log de Laravel).");
                } else if (anclas < 1) {
                    fallos.add("GET / responde 200 pero el contenido NO contiene \"" + ANCLA_HOME
                            + "\": no es la portada real (¿otro sitio en el puerto 80 o vista en caché obsoleta?).");
                } else if (roturas > 0) {
                    fallos.add("GET / muestra errores de la aplicación (Whoops/SQLSTATE/Fatal error). "
                            + "Revise storage/logs/laravel.log y APP_DEBUG.");
                } else {
                    ctx.ok("GET / → 200 con contenido real (" + bytes + " bytes, ancla \"" + ANCLA_HOME + "\")");
                }
            }
        } finally {
            ctx.ejecutar("rm -f " + tmpHome, 30);
        }

        // ================================================== 2) Página de login
        String tmpLogin = "/tmp/bolsa-val-" + ctx.institucion.getSlug() + "-login.html";
        try {
            CommandResult login = ctx.ejecutar(
                    "curl -s -m 20 -H 'Host: " + dominio + "' "
                            + "-o " + tmpLogin + " -w '%{http_code}' http://127.0.0.1/login", 60);
            String codigo = login.primeraLinea().trim();
            if (!"200".equals(codigo)) {
                fallos.add("GET /login → HTTP " + (codigo.isBlank() ? "(sin respuesta)" : codigo)
                        + ". La ruta de acceso no responde: revise rutas en caché (php artisan route:clear).");
            } else {
                CommandResult checks = ctx.ejecutar(
                        "grep -c 'type=\"password\"' " + tmpLogin + "; "
                                + "grep -ci 'login' " + tmpLogin + "; "
                                + "grep -cE 'Whoops|SQLSTATE|Fatal error' " + tmpLogin, 60);
                String[] d = checks.salida().trim().split("\\s+");
                long pw = d.length > 0 ? aEntero(d[0]) : 0;
                long loginTxt = d.length > 1 ? aEntero(d[1]) : 0;
                long rot = d.length > 2 ? aEntero(d[2]) : 0;
                if (pw < 1 || loginTxt < 1) {
                    fallos.add("GET /login → 200 pero sin el formulario real (falta el campo de contraseña"
                            + (pw < 1 ? "" : " o el texto Login") + "). No es la vista de acceso.");
                } else if (rot > 0) {
                    fallos.add("GET /login muestra errores de la aplicación (Whoops/SQLSTATE/Fatal error).");
                } else {
                    ctx.ok("GET /login → 200 con formulario de acceso real (campo password presente)");
                }
            }
        } finally {
            ctx.ejecutar("rm -f " + tmpLogin, 30);
        }

        // ================================================ 3) .env en producción
        CommandResult debug = ctx.ejecutar(
                "grep -E '^APP_DEBUG=' " + shared + "/.env 2>/dev/null || true", 30);
        if (!debug.salida().contains("APP_DEBUG=false")) {
            fallos.add("shared/.env no tiene APP_DEBUG=false (leído: '"
                    + debug.primeraLinea().trim() + "'): la app expondría trazas en producción.");
        } else {
            ctx.ok("APP_DEBUG=false verificado en shared/.env");
        }

        // ================================================= 4) Artefactos/current
        CommandResult artefactos = ctx.ejecutar(
                "readlink -f " + current + " 2>/dev/null; "
                        + "test -f " + current + "/artisan && echo ARTISAN_OK; "
                        + "test -f " + current + "/public/build/manifest.json && echo MANIFEST_OK; "
                        + "test -f " + current + "/vendor/autoload.php && echo VENDOR_OK", 60);
        String art = artefactos.salida();
        if (!art.contains("ARTISAN_OK")) {
            fallos.add("'current' no resuelve a una release con artisan: el enlace está roto o vacío.");
        }
        if (!art.contains("MANIFEST_OK")) {
            fallos.add("Falta public/build/manifest.json en el release activo: el frontend no compiló.");
        }
        if (!art.contains("VENDOR_OK")) {
            fallos.add("Falta vendor/autoload.php en el release activo: Composer no instaló dependencias.");
        }
        if (art.contains("ARTISAN_OK") && art.contains("MANIFEST_OK") && art.contains("VENDOR_OK")) {
            ctx.ok("Release activa verificada: artisan + manifest de Vite + autoloader");
        }

        // ==================================================== 5) migraciones
        CommandResult status = ctx.ejecutar(
                "cd " + current + " && sudo -u www-data php artisan migrate:status 2>&1 | tail -5", 300);
        if (!status.exito()) {
            fallos.add("php artisan migrate:status falló: " + recortar(status.salida())
                    + ". El esquema no es consistente.");
        } else {
            ctx.ok("migrate:status correcto");
        }

        // ====================================================== 6) servicios
        String fpm = ctx.valores.getOrDefault("phpFpmService", "php" + ctx.phpObjetivo() + "-fpm");
        CommandResult servicios = ctx.ejecutar(
                "for s in nginx " + fpm + " " + ctx.servicioMysql() + "; do "
                        + "printf '%s=%s\\n' \"$s\" \"$(systemctl is-active $s 2>/dev/null)\"; done", 120);
        for (String linea : servicios.salida().split("\n")) {
            if (linea.isBlank()) {
                continue;
            }
            if (!linea.contains("=active")) {
                fallos.add("El servicio " + linea.replace("=", " está '") + "' (debe estar active).");
            }
        }
        if (!servicios.salida().isBlank() && servicios.salida().contains("=active")
                && servicios.salida().split("=active").length - 1 >= 3) {
            ctx.ok("Servicios activos: nginx, " + fpm + ", " + ctx.servicioMysql());
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
