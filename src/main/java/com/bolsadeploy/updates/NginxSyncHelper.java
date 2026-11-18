package com.bolsadeploy.updates;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.DominioNginxStage;
import com.bolsadeploy.deployment.MigracionStage;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Inspección, alineación y verificación real de Nginx, PHP-FPM, workers y
 * cachés durante actualizaciones. Garantiza que el navegador realmente vea los
 * cambios tras actualizar desde GitHub.
 */
final class NginxSyncHelper {

    record NginxBlock(boolean explicit, String file, String ports, String root,
                      boolean hasProxy, boolean hasRedirect, int otherDomains, String serverNames) {
        boolean tienePort443() {
            return ports != null && ports.contains("443");
        }
    }

    record NginxEstado(boolean nginxActivo, boolean apacheActivo,
                       List<NginxBlock> explicitBlocks, List<NginxBlock> defaultBlocks) {

        boolean esSoloProxyPass() {
            if (explicitBlocks.isEmpty()) {
                return false;
            }
            for (NginxBlock b : explicitBlocks) {
                if (b.hasProxy() && (b.root() == null || b.root().isBlank())) {
                    return true;
                }
            }
            return false;
        }

        boolean tieneHttpsConfigurado() {
            for (NginxBlock b : explicitBlocks) {
                if (b.tienePort443()) {
                    return true;
                }
            }
            return false;
        }
    }

    private NginxSyncHelper() {
    }

    /** Detecta usuario root/sudo y el socket PHP-FPM real del VPS. */
    static void inicializarEntorno(DeploymentContext ctx) throws StageException {
        CommandResult uid = ctx.ejecutar("id -u 2>/dev/null || echo 1000", 15);
        boolean esRoot = "0".equals(uid.primeraLinea().trim());
        ctx.valores.put("esRoot", String.valueOf(esRoot));

        ctx.ejecutar("mkdir -p " + ctx.rutaBase() + "/releases "
                + ctx.rutaShared() + " " + ctx.rutaBackups(), 30);

        CommandResult sock = ctx.ejecutar(
                "ls -1 /run/php/php*-fpm.sock /var/run/php/php*-fpm.sock 2>/dev/null | sort -rV | head -1", 15);
        String sockPath = sock.primeraLinea().trim();
        if (!sockPath.isBlank()) {
            ctx.valores.put("socketFpm", "unix:" + sockPath);
            String baseName = sockPath.substring(sockPath.lastIndexOf('/') + 1).replace(".sock", "");
            ctx.valores.put("phpFpmService", baseName);
        }
    }

    /** Analiza la configuración cargada de Nginx para el dominio de la institución. */
    static NginxEstado inspeccionarNginx(DeploymentContext ctx, String dominio) throws StageException {
        CommandResult ngxActive = ctx.ejecutar("systemctl is-active nginx 2>/dev/null || true", 15);
        boolean nginxActivo = "active".equals(ngxActive.primeraLinea().trim());

        CommandResult apActive = ctx.ejecutar("systemctl is-active apache2 2>/dev/null || true", 15);
        boolean apacheActivo = "active".equals(apActive.primeraLinea().trim());

        List<NginxBlock> explicit = new ArrayList<>();
        List<NginxBlock> defaults = new ArrayList<>();

        if (!nginxActivo) {
            return new NginxEstado(false, apacheActivo, explicit, defaults);
        }

        String pyScript = buildInspectScript(dominio);
        String b64 = Base64.getEncoder().encodeToString(pyScript.getBytes(StandardCharsets.UTF_8));
        String tmpPy = "/tmp/vpspilot-ngx-inspect-" + ctx.institucion.getSlug() + ".py";

        CommandResult res = ctx.ejecutar(
                "echo " + b64 + " | base64 -d > " + tmpPy
                        + " && " + ctx.sudo("nginx -T 2>/dev/null") + " | python3 " + tmpPy
                        + " ; rm -f " + tmpPy, 60);

        for (String linea : res.salida().split("\\R+")) {
            if (!linea.startsWith("BLOCK|")) {
                continue;
            }
            String[] p = linea.split("\\|", -1);
            if (p.length >= 9) {
                boolean exp = "EXPLICIT".equals(p[1]);
                String file = p[2].trim();
                String ports = p[3].trim();
                String root = p[4].trim();
                boolean proxy = "YES".equals(p[5].trim());
                boolean redir = "YES".equals(p[6].trim());
                int others = 0;
                try {
                    others = Integer.parseInt(p[7].trim());
                } catch (NumberFormatException ignored) {
                }
                String names = p[8].trim();
                NginxBlock block = new NginxBlock(exp, file, ports, root, proxy, redir, others, names);
                if (exp) {
                    explicit.add(block);
                } else {
                    defaults.add(block);
                }
            }
        }
        return new NginxEstado(nginxActivo, apacheActivo, explicit, defaults);
    }

    /**
     * Si el dominio ya estaba alojado manualmente en otra carpeta del VPS, adopta
     * su .env, storage y uploads en shared/ (sin sobrescribir si ya existen).
     */
    static void adoptarDesdeRaizPreviaSiNecesario(DeploymentContext ctx, NginxEstado estado)
            throws StageException {
        String base = ctx.rutaBase();
        String shared = ctx.rutaShared();

        for (NginxBlock b : estado.explicitBlocks()) {
            String r = normalizarRuta(b.root());
            if (r.isBlank() || r.startsWith(base) || "/var/www/html".equals(r)) {
                continue;
            }
            // Directorio raíz del proyecto previo (si root terminaba en /public o /dist, subir 1 nivel)
            String proyectoPrevio = (r.endsWith("/public") || r.endsWith("/dist") || r.endsWith("/build"))
                    ? r.substring(0, r.lastIndexOf('/'))
                    : r;

            // 1. Adoptar .env si shared/.env no existe o está vacío
            CommandResult tieneEnvShared = ctx.ejecutar("test -s " + shared + "/.env && echo SI", 15);
            if (!"SI".equals(tieneEnvShared.primeraLinea().trim())) {
                CommandResult envPrev = ctx.ejecutar(
                        "for p in \"" + r + "/.env\" \"" + proyectoPrevio + "/.env\"; do "
                                + "if [ -s \"$p\" ]; then echo \"$p\"; break; fi; done", 15);
                String rutaEnvPrev = envPrev.primeraLinea().trim();
                if (!rutaEnvPrev.isBlank()) {
                    ctx.ejecutar(ctx.sudo("cp -a \"" + rutaEnvPrev + "\" " + shared + "/.env")
                            + " && " + ctx.sudo("chown www-data:www-data " + shared + "/.env")
                            + " && " + ctx.sudo("chmod 640 " + shared + "/.env"), 30);
                    ctx.log("  [i] Adoptado archivo .env existente desde " + rutaEnvPrev + " hacia shared/.env");
                }
            }

            // 2. Adoptar storage/ si existe en instalación previa y shared/storage está vacío
            CommandResult storageVacio = ctx.ejecutar(
                    "find " + shared + "/storage -type f 2>/dev/null | head -1", 15);
            if (storageVacio.salida().isBlank()) {
                CommandResult stPrev = ctx.ejecutar(
                        "for p in \"" + proyectoPrevio + "/storage\" \"" + r + "/storage\"; do "
                                + "if [ -d \"$p\" ] && [ -n \"$(ls -A \"$p\" 2>/dev/null)\" ]; then echo \"$p\"; break; fi; done", 15);
                String rutaStPrev = stPrev.primeraLinea().trim();
                if (!rutaStPrev.isBlank()) {
                    ctx.ejecutar("mkdir -p " + shared + "/storage && cp -rn \"" + rutaStPrev + "/.\" "
                            + shared + "/storage/ 2>/dev/null || true", 120);
                    ctx.log("  [i] Copiada carpeta storage existente desde " + rutaStPrev + " hacia shared/storage");
                }
            }

            // 3. Adoptar uploads/ si existe y shared/uploads está vacío
            CommandResult uploadsVacio = ctx.ejecutar(
                    "find " + shared + "/uploads -type f 2>/dev/null | head -1", 15);
            if (uploadsVacio.salida().isBlank()) {
                CommandResult upPrev = ctx.ejecutar(
                        "for p in \"" + r + "/uploads\" \"" + proyectoPrevio + "/public/uploads\" \"" + proyectoPrevio + "/uploads\"; do "
                                + "if [ -d \"$p\" ] && [ -n \"$(ls -A \"$p\" 2>/dev/null)\" ]; then echo \"$p\"; break; fi; done", 15);
                String rutaUpPrev = upPrev.primeraLinea().trim();
                if (!rutaUpPrev.isBlank()) {
                    ctx.ejecutar("mkdir -p " + shared + "/uploads && cp -rn \"" + rutaUpPrev + "/.\" "
                            + shared + "/uploads/ 2>/dev/null || true", 120);
                    ctx.log("  [i] Copiada carpeta uploads existente desde " + rutaUpPrev + " hacia shared/uploads");
                }
            }
        }
    }

    /**
     * Garantiza que Nginx apunte hacia {@code <current>/public} y no sirva una
     * carpeta antigua ni cachee archivos JS/CSS por 7 días.
     */
    static boolean asegurarNginxApuntaACurrent(DeploymentContext ctx, NginxEstado estado)
            throws StageException {
        if (!estado.nginxActivo()) {
            if (estado.apacheActivo()) {
                throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                        "En el servidor VPS está corriendo Apache2 (no Nginx). VPSPilot desplegó en '"
                                + ctx.rutaReleaseActual() + "/public', pero Apache puede seguir sirviendo otra carpeta.");
            }
            ctx.log("  [!] Aviso: Nginx no está activo en el VPS; se omite alineación de vhost.");
            return false;
        }

        String dominio = ctx.institucion.dominioPrincipal();
        String esperadoPublic = normalizarRuta(ctx.rutaReleaseActual() + "/public");
        boolean huboCambios = false;

        if (estado.explicitBlocks().isEmpty()) {
            ctx.log("  [i] No existe configuración Nginx específica para '" + dominio
                    + "'. Creando vhost apuntando a " + esperadoPublic + "…");
            new DominioNginxStage().ejecutar(ctx);
            return true;
        }

        for (NginxBlock b : estado.explicitBlocks()) {
            boolean sinRoot = b.root() == null || b.root().isBlank();
            if (sinRoot && b.hasProxy()) {
                ctx.log("  [i] Vhost en " + b.file() + " usa proxy_pass (aplicación backend).");
                continue;
            }
            if (sinRoot && b.hasRedirect()) {
                continue; // bloque de redirección HTTP -> HTTPS de Certbot
            }
            String actualRoot = normalizarRuta(b.root());
            if (!esperadoPublic.equals(actualRoot)) {
                if (b.otherDomains() > 0) {
                    throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                            "Nginx atiende '" + dominio + "' en un bloque compartido (" + b.file()
                                    + ") con otros dominios y root actual '" + actualRoot
                                    + "' distinto de '" + esperadoPublic
                                    + "'. Ajuste el root manualmente para no afectar otros sitios.");
                }
                if (b.file().isBlank()) {
                    throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                            "Nginx atiende '" + dominio + "' desde '" + actualRoot
                                    + "' en lugar de '" + esperadoPublic + "'.");
                }

                ctx.log("  [i] Alineando root en " + b.file() + ": '" + actualRoot + "' → '" + esperadoPublic + "'…");
                actualizarRootEnVhost(ctx, b.file(), dominio, esperadoPublic);
                huboCambios = true;
            }
        }

        // Detectar si la release actual es PHP (index.php) o SPA/estática (index.html)
        CommandResult stackRes = ctx.ejecutar(
                "if [ -f " + esperadoPublic + "/index.php ]; then echo PHP; "
                        + "elif [ -f " + esperadoPublic + "/index.html ]; then echo STATIC; "
                        + "else echo PHP; fi", 15);
        boolean esPhp = !"STATIC".equals(stackRes.primeraLinea().trim());

        // Mejorar cabecera Cache-Control y normalizar try_files limpio (sin @app_fallback ni doble fallback) en vhosts de BolsaDeploy
        java.util.Set<String> candidatosVhost = new java.util.LinkedHashSet<>();
        candidatosVhost.add("/etc/nginx/sites-available/bolsa-" + ctx.institucion.getSlug() + ".conf");
        for (NginxBlock b : estado.explicitBlocks()) {
            if (b.file() != null && !b.file().isBlank()) {
                candidatosVhost.add(b.file());
            }
        }
        String tryEsperado = esPhp ? "/index.php?$query_string;" : "/index.html;";
        String tryOpuesto = esPhp ? "try_files $uri $uri/ /index.html;" : "try_files $uri $uri/ /index.php?$query_string;";
        for (String vhostConf : candidatosVhost) {
            CommandResult tieneVhostLegacy = ctx.ejecutar(
                    "test -f \"" + vhostConf + "\" && grep -q 'Generado por BolsaDeploy' \"" + vhostConf + "\""
                            + " && { { grep -q 'js|css|png' \"" + vhostConf + "\" && grep -q 'expires 7d;' \"" + vhostConf + "\"; }"
                            + " || grep -qF '/index.php?$query_string /index.html;' \"" + vhostConf + "\""
                            + " || grep -qF '@app_fallback' \"" + vhostConf + "\""
                            + " || grep -qF '" + tryOpuesto + "' \"" + vhostConf + "\""
                            + " || ! grep -qF '" + tryEsperado + "' \"" + vhostConf + "\"; } && echo SI", 15);
            if ("SI".equals(tieneVhostLegacy.primeraLinea().trim())) {
                modernizarCacheStaticEnVhost(ctx, vhostConf, esPhp);
                huboCambios = true;
            }
        }

        return huboCambios;
    }

    /**
     * Limpia cachés de aplicación (Laravel), recarga PHP-FPM (OPcache + realpath_cache),
     * reinicia workers (Supervisor / PM2) y recarga Nginx tras conmutar {@code current}.
     */
    static void recargarServiciosPostConmutacion(DeploymentContext ctx) throws StageException {
        String current = ctx.rutaReleaseActual();
        String slug = ctx.institucion.getSlug();

        // 1. Cachés seguras de Laravel en current (si aplica)
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + current + "/artisan && echo SI", 15);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            ctx.log("  Limpiando y regenerando cachés seguras de Laravel en current…");
            MigracionStage.optimizarCachesLaravel(ctx, current);
        }

        // 2. Recargar PHP-FPM para limpiar OPcache y realpath_cache (zero-downtime)
        CommandResult fpmReload = ctx.ejecutar(
                "RELOADED=''; "
                        + "for s in $(systemctl list-units --type=service --state=running --plain --no-legend 'php*-fpm*' 2>/dev/null | awk '{print $1}'); do "
                        + ctx.sudo("systemctl reload \"$s\"") + " 2>/dev/null || " + ctx.sudo("systemctl restart \"$s\"") + " 2>/dev/null || true; "
                        + "RELOADED=\"$RELOADED $s\"; "
                        + "done; echo \"$RELOADED\"", 60);
        String fpmList = fpmReload.primeraLinea().trim();
        if (!fpmList.isBlank()) {
            ctx.ok("PHP-FPM recargado (OPcache/realpath limpiado): " + fpmList);
        }

        // 3. Supervisor worker
        ctx.ejecutar("command -v supervisorctl >/dev/null 2>&1 && { "
                + ctx.sudo("supervisorctl restart bolsa-" + slug + "-worker") + " >/dev/null 2>&1 || "
                + ctx.sudo("supervisorctl restart " + slug) + " >/dev/null 2>&1 || true; }", 60);

        // 4. PM2 (apps Node.js) y servicios systemd personalizados si existen
        ctx.ejecutar("command -v pm2 >/dev/null 2>&1 && { "
                + "pm2 reload " + slug + " >/dev/null 2>&1 || "
                + "sudo -u www-data pm2 reload " + slug + " >/dev/null 2>&1 || true; }", 60);
        ctx.ejecutar("for s in " + slug + ".service vpspilot-" + slug + ".service; do "
                + "systemctl is-active --quiet \"$s\" 2>/dev/null && " + ctx.sudo("systemctl restart \"$s\"") + " || true; "
                + "done", 60);

        // 5. Recargar Nginx
        CommandResult ngx = ctx.ejecutar(
                ctx.sudo("nginx -t") + " >/dev/null 2>&1 && (" + ctx.sudo("systemctl reload nginx")
                        + " 2>/dev/null || " + ctx.sudo("systemctl restart nginx") + " 2>/dev/null || true)", 60);
        if (ngx.exito()) {
            ctx.ok("Nginx recargado tras cambio de release");
        }
    }

    /**
     * Comprueba respuesta HTTP y HTTPS reales, sonda física (Canary Probe),
     * manifiesto de Vite y rutas interactivas (como {@code /login} en Laravel)
     * más posibles excepciones nuevas en {@code laravel.log} para evitar
     * promocionar despliegues con {@code 500 Server Error} en producción.
     */
    static String validarHttpConCanary(DeploymentContext ctx, NginxEstado estado, String objetivoSha) {
        String dominio = ctx.institucion.dominioPrincipal();
        String current = ctx.rutaReleaseActual();
        String currentPublic = current + "/public";
        String shared = ctx.rutaShared();
        String tmpHtml = "/tmp/vpspilot-upd-val-" + ctx.institucion.getSlug() + ".html";
        String tmpLogin = "/tmp/vpspilot-upd-login-" + ctx.institucion.getSlug() + ".html";
        String tokenName = "vpspilot-probe-" + System.currentTimeMillis() + ".txt";
        String probeFile = null;

        try {
            // 0. Línea base del log de Laravel ANTES de las sondas HTTP (solo evaluaremos errores NUEVOS)
            CommandResult baseLog = ctx.ejecutar(
                    "wc -l < " + shared + "/storage/logs/laravel.log 2>/dev/null || echo 0", 15);
            long lineasLogPrevias = aEntero(baseLog.primeraLinea().trim());

            // 0b. Verificar manifiesto Vite compilado si es proyecto Laravel + Vite
            CommandResult faltaManifest = ctx.ejecutar(
                    "test -f " + current + "/artisan && ls " + current + "/vite.config.* >/dev/null 2>&1"
                            + " && ! test -s " + currentPublic + "/build/manifest.json && echo FALTA", 15);
            if ("FALTA".equals(faltaManifest.primeraLinea().trim())) {
                return "Falta public/build/manifest.json (assets Vite sin compilar; las vistas con @vite devolverán 500 Server Error).";
            }

            // 1. Prueba de la página principal HTTP (siguiendo redirect local si existe)
            CommandResult r = ctx.ejecutar(
                    "curl -skL -m 25 --resolve " + dominio + ":80:127.0.0.1 --resolve " + dominio + ":443:127.0.0.1"
                            + " -o " + tmpHtml + " -w '%{http_code}' http://" + dominio + "/", 90);
            String codigo = r.primeraLinea().trim();
            int st = (int) aEntero(codigo);
            if (st >= 500 || st == 0) {
                String detalle = diagnosticoErrorHttp(ctx, tmpHtml, lineasLogPrevias);
                return "GET / responde HTTP " + (codigo.isBlank() ? "(sin respuesta)" : codigo)
                        + (detalle.isBlank() ? "" : "\nCausa en servidor: " + detalle);
            }
            CommandResult c = ctx.ejecutar(
                    "grep -cE 'Whoops|SQLSTATE|Fatal error' " + tmpHtml + "; wc -c < " + tmpHtml, 60);
            String[] d = c.salida().trim().split("\\s+");
            long rotos = d.length > 0 ? aEntero(d[0]) : 0;
            if (rotos > 0) {
                return "la página muestra errores críticos en el HTML (Fatal error / SQLSTATE)";
            }

            // 2. Si hay vhost HTTPS (443), comprobar también HTTPS directo por SNI local
            boolean check443 = estado != null && estado.tieneHttpsConfigurado();
            if (!check443) {
                CommandResult p443 = ctx.ejecutar("ss -tln 2>/dev/null | grep -q ':443 ' && echo SI", 15);
                if ("SI".equals(p443.primeraLinea().trim()) && estado != null && !estado.explicitBlocks().isEmpty()) {
                    check443 = estado.tieneHttpsConfigurado();
                }
            }
            if (check443) {
                CommandResult rSsl = ctx.ejecutar(
                        "curl -skL -m 25 --resolve " + dominio + ":443:127.0.0.1"
                                + " -o " + tmpHtml + " -w '%{http_code}' https://" + dominio + "/", 90);
                int stSsl = (int) aEntero(rSsl.primeraLinea().trim());
                if (stSsl >= 500 || stSsl == 0) {
                    String detalle = diagnosticoErrorHttp(ctx, tmpHtml, lineasLogPrevias);
                    return "GET HTTPS / responde HTTP " + rSsl.primeraLinea().trim()
                            + (detalle.isBlank() ? "" : "\nCausa en servidor: " + detalle);
                }
            }

            // 3. Sonda física Canary dentro de current/public (cuando Nginx sirve archivos desde root)
            if (estado != null && estado.nginxActivo() && !estado.esSoloProxyPass()
                    && objetivoSha != null && !objetivoSha.isBlank()) {
                CommandResult resolved = ctx.ejecutar("readlink -f " + currentPublic + " 2>/dev/null || true", 15);
                String servedDir = resolved.primeraLinea().trim();
                if (!servedDir.isBlank()) {
                    probeFile = servedDir + "/" + tokenName;
                    ctx.ejecutar("printf '%s' " + objetivoSha + " > " + probeFile
                            + " && chmod 644 " + probeFile
                            + " && " + ctx.sudo("chown www-data:www-data " + probeFile + " 2>/dev/null || true"), 30);

                    String urlHttp = "http://" + dominio + "/" + tokenName;
                    CommandResult probeHttp = ctx.ejecutar(
                            "curl -skL -m 15 --resolve " + dominio + ":80:127.0.0.1 --resolve " + dominio + ":443:127.0.0.1 "
                                    + urlHttp, 30);
                    boolean okHttp = probeHttp.salida().trim().contains(objetivoSha);

                    boolean okHttps = true;
                    if (check443) {
                        CommandResult probeSsl = ctx.ejecutar(
                                "curl -sk -m 15 --resolve " + dominio + ":443:127.0.0.1 https://" + dominio + "/" + tokenName, 30);
                        okHttps = probeSsl.salida().trim().contains(objetivoSha);
                    }

                    if (!okHttp || !okHttps) {
                        NginxEstado post = inspeccionarNginx(ctx, dominio);
                        StringBuilder detalle = new StringBuilder();
                        for (NginxBlock b : post.explicitBlocks()) {
                            detalle.append(b.file()).append(" (ports=").append(b.ports())
                                    .append(", root=").append(b.root()).append("); ");
                        }
                        if (detalle.isEmpty()) {
                            for (NginxBlock b : post.defaultBlocks()) {
                                detalle.append(b.file()).append(" [default] (root=").append(b.root()).append("); ");
                            }
                        }
                        return "NGINX_MISMATCH: Nginx responde HTTP " + codigo
                                + " pero NO está sirviendo la carpeta nueva (" + servedDir + "). "
                                + "Configuración detectada en Nginx para '" + dominio + "': "
                                + (detalle.isEmpty() ? "(sin coincidencia)" : detalle.toString());
                    }
                    ctx.ok("Verificación en vivo confirmada: Nginx sirve la release " + objetivoSha.substring(0, Math.min(8, objetivoSha.length())));
                }
            }

            // 4. Verificación funcional profunda en Laravel: probar /login (usa @vite, sesión, vistas y BD) y /up
            CommandResult esLaravel = ctx.ejecutar("test -f " + current + "/artisan && echo SI", 15);
            if ("SI".equals(esLaravel.primeraLinea().trim())) {
                String scheme = check443 ? "https://" : "http://";
                CommandResult rLogin = ctx.ejecutar(
                        "curl -skL -m 25 --resolve " + dominio + ":80:127.0.0.1 --resolve " + dominio + ":443:127.0.0.1"
                                + " -o " + tmpLogin + " -w '%{http_code}' " + scheme + dominio + "/login", 60);
                int stLogin = (int) aEntero(rLogin.primeraLinea().trim());
                if (stLogin >= 500 || stLogin == 0) {
                    String detalle = diagnosticoErrorHttp(ctx, tmpLogin, lineasLogPrevias);
                    return "GET /login responde HTTP " + rLogin.primeraLinea().trim()
                            + (detalle.isBlank() ? "" : "\nCausa en servidor: " + detalle);
                }
                if (stLogin >= 200 && stLogin < 400) {
                    CommandResult rotLogin = ctx.ejecutar(
                            "grep -cE 'Whoops|SQLSTATE|Fatal error' " + tmpLogin + " 2>/dev/null || echo 0", 30);
                    if (aEntero(rotLogin.primeraLinea().trim()) > 0) {
                        return "GET /login muestra errores críticos en el HTML (Fatal error / SQLSTATE)";
                    }
                    ctx.ok("Ruta interactiva /login verificada en vivo (HTTP " + stLogin + ")");
                }

                CommandResult rUp = ctx.ejecutar(
                        "curl -skL -m 15 --resolve " + dominio + ":80:127.0.0.1 --resolve " + dominio + ":443:127.0.0.1"
                                + " -o /dev/null -w '%{http_code}' " + scheme + dominio + "/up", 30);
                int stUp = (int) aEntero(rUp.primeraLinea().trim());
                if (stUp >= 500) {
                    String detalle = diagnosticoErrorHttp(ctx, tmpHtml, lineasLogPrevias);
                    return "GET /up responde HTTP " + rUp.primeraLinea().trim()
                            + (detalle.isBlank() ? "" : "\nCausa en servidor: " + detalle);
                }

                // 5. Detectar excepciones fatales NUEVAS registradas en laravel.log durante las pruebas en vivo
                CommandResult nuevosErr = ctx.ejecutar(
                        "if [ -f " + shared + "/storage/logs/laravel.log ]; then "
                                + "tail -n +" + (lineasLogPrevias + 1) + " " + shared + "/storage/logs/laravel.log 2>/dev/null "
                                + "| grep -E 'ViteManifestNotFoundException|MissingAppKeyException|SQLSTATE\\[|PDOException|QueryException|FatalError|ParseError|ViewException|BindingResolutionException' "
                                + "| head -n 2; fi", 20);
                String errNuevo = nuevosErr.salida() == null ? "" : nuevosErr.salida().trim();
                if (!errNuevo.isBlank()) {
                    String rec = errNuevo.replaceAll("\\s+", " ");
                    if (rec.length() > 350) rec = rec.substring(0, 350) + "…";
                    return "Se registró una excepción crítica en laravel.log durante la comprobación en vivo: " + rec;
                }
            }

            return null;
        } catch (StageException e) {
            return "no se pudo comprobar el sitio: " + e.getMessage();
        } finally {
            try {
                if (probeFile != null) {
                    ctx.ejecutar("rm -f " + probeFile, 20);
                }
                ctx.ejecutar("rm -f " + tmpHtml + " " + tmpLogin, 20);
            } catch (StageException ignored) {
            }
        }
    }

    /** Extrae las líneas reales de error de Nginx, Laravel (priorizando errores nuevos) y HTML para diagnosticar HTTP 500. */
    private static String diagnosticoErrorHttp(DeploymentContext ctx, String tmpHtml, long lineasLogPrevias) {
        String slug = ctx.institucion.getSlug();
        String shared = ctx.rutaShared();
        String larLog = shared + "/storage/logs/laravel.log";
        try {
            CommandResult d = ctx.ejecutar(
                    "NGX=$(" + ctx.sudo("tail -n 3 /var/log/nginx/" + slug + "-error.log 2>/dev/null || tail -n 3 /var/log/nginx/error.log 2>/dev/null") + "); "
                            + "LAR=$(tail -n +" + (lineasLogPrevias + 1) + " " + larLog + " 2>/dev/null "
                            + "| grep -E '\\.(ERROR|CRITICAL|EMERGENCY):|Exception|Error:' | tail -n 2); "
                            + "if [ -z \"$LAR\" ]; then LAR=$(grep -E '\\.(ERROR|CRITICAL|EMERGENCY):|Exception|Error:' " + larLog + " 2>/dev/null | tail -n 2); fi; "
                            + "HTM=$(grep -io '<title[^>]*>[^<]*</title>' " + tmpHtml + " 2>/dev/null | head -1); "
                            + "echo \"NGX: $NGX\"; echo \"LAR: $LAR\"; echo \"HTM: $HTM\"", 20);
            StringBuilder sb = new StringBuilder();
            String larMsg = "";
            String ngxMsg = "";
            String htmMsg = "";
            for (String linea : d.salida().split("\\R+")) {
                String l = linea.trim();
                if (l.isBlank() || l.equals("NGX:") || l.equals("LAR:") || l.equals("HTM:")) {
                    continue;
                }
                ctx.log("    [DIAG] " + l);
                if (l.startsWith("LAR:")) {
                    larMsg = l;
                } else if (l.startsWith("NGX:")) {
                    ngxMsg = l;
                } else if (l.startsWith("HTM:")) {
                    htmMsg = l;
                }
            }
            if (!larMsg.isBlank()) sb.append(larMsg);
            if (!ngxMsg.isBlank()) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append(ngxMsg);
            }
            if (sb.isEmpty() && !htmMsg.isBlank()) {
                sb.append(htmMsg);
            }
            String res = sb.toString().trim();
            return res.length() > 400 ? res.substring(0, 400) + "…" : res;
        } catch (Exception ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Edición segura de vhosts de Nginx
    // ------------------------------------------------------------------

    private static void actualizarRootEnVhost(DeploymentContext ctx, String vhostFile,
                                              String dominio, String nuevoRoot) throws StageException {
        String backup = vhostFile + ".vpspilot.bak";
        ctx.exigirExito(ctx.sudo("cp -a \"" + vhostFile + "\" \"" + backup + "\""), 30,
                "Respaldo temporal de configuración Nginx");

        String pyPatch = buildPatchRootScript(dominio, nuevoRoot);
        String b64 = Base64.getEncoder().encodeToString(pyPatch.getBytes(StandardCharsets.UTF_8));
        String tmpPy = "/tmp/vpspilot-ngx-patch-" + ctx.institucion.getSlug() + ".py";

        CommandResult res = ctx.ejecutar(
                "echo " + b64 + " | base64 -d > " + tmpPy
                        + " && " + ctx.sudo("python3 " + tmpPy + " \"" + vhostFile + "\"")
                        + " ; rm -f " + tmpPy, 45);
        if (!res.exito()) {
            ctx.ejecutar(ctx.sudo("cp -a \"" + backup + "\" \"" + vhostFile + "\"") + " && "
                    + ctx.sudo("rm -f \"" + backup + "\""), 30);
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo actualizar 'root' en " + vhostFile + ": " + res.salida().trim());
        }

        CommandResult test = ctx.ejecutar(ctx.sudo("nginx -t"), 45);
        if (!test.exito()) {
            ctx.ejecutar(ctx.sudo("cp -a \"" + backup + "\" \"" + vhostFile + "\"") + " && "
                    + ctx.sudo("rm -f \"" + backup + "\""), 30);
            throw new StageException(StageException.Tipo.FATAL,
                    "Al actualizar 'root' en " + vhostFile + " falló 'nginx -t' (revertido): " + test.salida().trim());
        }
        ctx.ejecutar(ctx.sudo("rm -f \"" + backup + "\""), 20);
        ctx.ok("Nginx actualizado (" + vhostFile + "): root → " + nuevoRoot);
    }

    private static void modernizarCacheStaticEnVhost(DeploymentContext ctx, String vhostFile, boolean esPhp)
            throws StageException {
        String backup = vhostFile + ".vpspilot.bak";
        ctx.ejecutar(ctx.sudo("cp -a \"" + vhostFile + "\" \"" + backup + "\""), 20);
        String pyCache = buildModernizeCacheScript(esPhp);
        String b64 = Base64.getEncoder().encodeToString(pyCache.getBytes(StandardCharsets.UTF_8));
        String tmpPy = "/tmp/vpspilot-ngx-cache-" + ctx.institucion.getSlug() + ".py";
        ctx.ejecutar("echo " + b64 + " | base64 -d > " + tmpPy + " && "
                + ctx.sudo("python3 " + tmpPy + " \"" + vhostFile + "\"") + " ; rm -f " + tmpPy, 30);
        CommandResult test = ctx.ejecutar(ctx.sudo("nginx -t"), 30);
        if (!test.exito()) {
            ctx.ejecutar(ctx.sudo("cp -a \"" + backup + "\" \"" + vhostFile + "\""), 20);
        } else {
            ctx.ok("Reglas de Nginx y caché actualizadas en " + vhostFile + " (modo " + (esPhp ? "PHP/Laravel" : "SPA estática") + ")");
        }
        ctx.ejecutar(ctx.sudo("rm -f \"" + backup + "\""), 20);
    }

    static String buildModernizeCacheScript() {
        return buildModernizeCacheScript(true);
    }

    static String buildModernizeCacheScript(boolean esPhp) {
        String tryRoot = esPhp
                ? "try_files $uri $uri/ /index.php?$query_string;"
                : "try_files $uri $uri/ /index.html;";
        String tryHtml = esPhp
                ? "try_files $uri =404;"
                : "try_files $uri /index.html =404;";
        return """
                import re, sys
                path = sys.argv[1]
                try_root = '%s'
                try_html = '%s'

                with open(path, 'r', encoding='utf-8') as f:
                    txt = f.read()
                orig = txt

                def remove_named_location(text, loc_name):
                    pat = re.compile(r'^[ \\t]*location\\s+' + re.escape(loc_name) + r'\\s*\\{', re.MULTILINE)
                    while True:
                        m = pat.search(text)
                        if not m:
                            break
                        start = m.start()
                        idx = m.end() - 1
                        depth = 0
                        end = len(text)
                        for i in range(idx, len(text)):
                            ch = text[i]
                            if ch == '{':
                                depth += 1
                            elif ch == '}':
                                depth -= 1
                                if depth == 0:
                                    end = i + 1
                                    break
                        while end < len(text) and text[end] in (' ', '\\t', '\\r', '\\n'):
                            if text[end] == '\\n':
                                end += 1
                                break
                            end += 1
                        text = text[:start] + text[end:]
                    return text

                # 1. Eliminar bloque @app_fallback previo (con llaves anidadas por 'if')
                txt = remove_named_location(txt, '@app_fallback')

                # 2. Normalizar location / { try_files ...; } al stack real sin if ni doble fallback
                pat_root_loc = re.compile(
                    r'(location\\s+/\\s*\\{\\s*)try_files\\s+\\$uri\\s+\\$uri/\\s+(?:/index\\.php\\?\\$query_string\\s+/index\\.html|@app_fallback|/index\\.php\\?\\$query_string|/index\\.html);(\\s*\\})'
                )
                txt = pat_root_loc.sub(lambda m: m.group(1) + try_root + m.group(2), txt)

                # 3. Normalizar location html/htm si apuntaba a @app_fallback o doble fallback
                pat_html_try = re.compile(
                    r'(location\\s+~\\*\\s+.{1,4}\\(\\?:html\\|htm\\)\\$\\s*\\{[^}]*?)try_files\\s+[^;]+;'
                )
                txt = pat_html_try.sub(lambda m: m.group(1) + try_html, txt)

                # 4. Actualizar bloque legado de caché 7d en js|css
                new_blocks = '''location ~* \\\\.(?:html|htm)$ {
                        add_header Cache-Control "no-cache, must-revalidate" always;
                        add_header X-Frame-Options "SAMEORIGIN" always;
                        add_header X-Content-Type-Options "nosniff" always;
                        add_header Referrer-Policy "same-origin" always;
                        ''' + try_html + '''
                    }

                    location ~* \\\\.(?:js|css)$ {
                        add_header Cache-Control "public, max-age=0, must-revalidate" always;
                        add_header X-Frame-Options "SAMEORIGIN" always;
                        add_header X-Content-Type-Options "nosniff" always;
                        add_header Referrer-Policy "same-origin" always;
                        access_log off;
                        try_files $uri =404;
                    }

                    location ~ \\\\.(png|jpg|jpeg|gif|ico|svg|webp|woff|woff2|ttf|eot|pdf)$ {
                        expires 7d;
                        access_log off;
                        try_files $uri =404;
                    }'''

                pat_7d = re.compile(r'location\\s*~\\s*.{1,3}\\(js\\|css\\|png\\|[^)]+\\)\\$\\s*\\{[^}]*expires\\s+7d;[^}]*\\}')
                if pat_7d.search(txt):
                    txt = pat_7d.sub(lambda _: new_blocks, txt)

                if txt != orig:
                    with open(path, 'w', encoding='utf-8') as f:
                        f.write(txt)
                """.formatted(tryRoot, tryHtml);
    }

    private static String buildInspectScript(String dominio) {
        String domLower = dominio.toLowerCase().trim();
        String wwwLower = domLower.startsWith("www.") ? domLower : "www." + domLower;
        String baseDom = domLower.startsWith("www.") ? domLower.substring(4) : domLower;
        return """
                import sys, os, re

                doms = {'%s', '%s', '%s'}
                lines = sys.stdin.read().splitlines()

                def match_name(token):
                    t = token.lower().strip()
                    if not t:
                        return False
                    if t in doms:
                        return True
                    if t.startswith('*.') and ('%s'.endswith(t[1:]) or '%s' == t[2:]):
                        return True
                    return False

                cur_file = ''
                in_server = False
                srv_depth = 0
                depth = 0
                buf = []
                blocks = []

                for raw in lines:
                    mfile = re.match(r'^#\\s*configuration file\\s+(.+):\\s*$', raw)
                    if mfile:
                        cur_file = os.path.realpath(mfile.group(1).strip())
                        continue
                    code = re.sub(r'#.*$', '', raw).strip()
                    if not code:
                        continue
                    # Split by tokens keeping { and }
                    if not in_server and re.search(r'\\bserver\\s*\\{', code):
                        in_server = True
                        srv_depth = depth
                        buf = [(raw, code, depth)]
                    elif in_server:
                        buf.append((raw, code, depth))

                    opens = code.count('{')
                    closes = code.count('}')
                    depth += opens - closes
                    if in_server and depth <= srv_depth:
                        blocks.append((cur_file, buf))
                        in_server = False
                        buf = []

                for fpath, blines in blocks:
                    ports = []
                    names = []
                    roots = []
                    has_proxy = False
                    has_redir = False
                    is_default = False
                    base_d = blines[0][2] + 1
                    cur_d = blines[0][2]
                    for _, c, _ in blines:
                        if 'proxy_pass' in c:
                            has_proxy = True
                        if re.search(r'\\breturn\\s+30[1278]\\b|\\brewrite\\s+', c):
                            has_redir = True
                        for stmt in c.split(';'):
                            s = stmt.strip()
                            if not s:
                                continue
                            ml = re.match(r'(?:server\\s*\\{\\s*)?listen\\s+(.+)$', s)
                            if ml:
                                val = ml.group(1)
                                ports.append(val)
                                if 'default_server' in val:
                                    is_default = True
                            mn = re.match(r'server_name\\s+(.+)$', s)
                            if mn:
                                for n in mn.group(1).split():
                                    names.append(n)
                                    if n == '_':
                                        is_default = True
                            if cur_d == base_d or (cur_d == base_d - 1 and 'location' not in c):
                                mr = re.match(r'(?:server\\s*\\{\\s*)?root\\s+(.+)$', s)
                                if mr:
                                    roots.append(mr.group(1).strip())
                        cur_d += c.count('{') - c.count('}')

                    explicit = any(match_name(n) for n in names)
                    others = sum(1 for n in names if n and not match_name(n) and n not in {'_', 'localhost'})
                    root_val = roots[-1] if roots else ''
                    ports_str = ','.join(ports)
                    names_str = ' '.join(names)
                    if explicit:
                        print(f'BLOCK|EXPLICIT|{fpath}|{ports_str}|{root_val}|{"YES" if has_proxy else "NO"}|{"YES" if has_redir else "NO"}|{others}|{names_str}')
                    elif is_default:
                        print(f'BLOCK|DEFAULT|{fpath}|{ports_str}|{root_val}|{"YES" if has_proxy else "NO"}|{"YES" if has_redir else "NO"}|{others}|{names_str}')
                """.formatted(domLower, wwwLower, baseDom, domLower, domLower);
    }

    private static String buildPatchRootScript(String dominio, String nuevoRoot) {
        String domLower = dominio.toLowerCase().trim();
        String wwwLower = domLower.startsWith("www.") ? domLower : "www." + domLower;
        String baseDom = domLower.startsWith("www.") ? domLower.substring(4) : domLower;
        return """
                import sys, re

                path = sys.argv[1]
                doms = {'%s', '%s', '%s'}
                nuevo = '%s'

                with open(path, 'r', encoding='utf-8') as f:
                    lines = f.readlines()

                def match_name(t):
                    t = t.lower().strip()
                    if t in doms:
                        return True
                    if t.startswith('*.') and ('%s'.endswith(t[1:]) or '%s' == t[2:]):
                        return True
                    return False

                # Pass 1: find line ranges of server blocks matching domain
                blocks = []
                in_srv = False
                srv_depth = 0
                depth = 0
                start_idx = 0
                names = []

                for idx, raw in enumerate(lines):
                    code = re.sub(r'#.*$', '', raw).strip()
                    if not in_srv and re.search(r'\\bserver\\s*\\{', code):
                        in_srv = True
                        srv_depth = depth
                        start_idx = idx
                        names = []
                    if in_srv:
                        mn = re.search(r'\\bserver_name\\s+([^;]+);', code)
                        if mn:
                            names.extend(mn.group(1).split())
                    depth += code.count('{') - code.count('}')
                    if in_srv and depth <= srv_depth:
                        if any(match_name(n) for n in names):
                            blocks.append((start_idx, idx))
                        in_srv = False

                if not blocks:
                    sys.exit(2)

                # Pass 2: replace root directives inside matching server blocks (depth == srv_depth + 1)
                changed = False
                for s_i, e_i in blocks:
                    d = 0
                    for i in range(s_i, e_i + 1):
                        raw = lines[i]
                        code = re.sub(r'#.*$', '', raw)
                        if i == s_i:
                            d = 1
                        if d == 1:
                            m = re.match(r'^(\\s*)root\\s+[^;]+;(.*)$', raw)
                            if m:
                                lines[i] = f"{m.group(1)}root {nuevo};{m.group(2)}\\n"
                                changed = True
                        if i > s_i:
                            d += code.count('{') - code.count('}')

                if changed:
                    with open(path, 'w', encoding='utf-8') as f:
                        f.writelines(lines)
                """.formatted(domLower, wwwLower, baseDom, nuevoRoot, domLower, domLower);
    }

    private static String normalizarRuta(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static long aEntero(String s) {
        if (s == null || s.isBlank()) return 0;
        String limpio = s.replaceAll("[^0-9]", "");
        if (limpio.isEmpty()) return 0;
        try {
            return Long.parseLong(limpio);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
