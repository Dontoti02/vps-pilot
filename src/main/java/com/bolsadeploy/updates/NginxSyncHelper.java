package com.bolsadeploy.updates;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.DominioNginxStage;
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
                    ctx.ejecutar(ctx.sudo("cp -a \"" + rutaEnvPrev + "\" " + shared + "/.env"
                            + " && chown www-data:www-data " + shared + "/.env"
                            + " && chmod 640 " + shared + "/.env"), 30);
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

        // Mejorar cabecera Cache-Control en vhosts generados por BolsaDeploy para evitar caché de 7d en JS/CSS
        String bolsaConf = "/etc/nginx/sites-available/bolsa-" + ctx.institucion.getSlug() + ".conf";
        CommandResult tieneLegacyCache = ctx.ejecutar(
                "test -f " + bolsaConf + " && grep -q 'Generado por BolsaDeploy' " + bolsaConf
                        + " && grep -q 'js|css|png' " + bolsaConf
                        + " && grep -q 'expires 7d;' " + bolsaConf + " && echo SI", 15);
        if ("SI".equals(tieneLegacyCache.primeraLinea().trim())) {
            modernizarCacheStaticEnVhost(ctx, bolsaConf);
            huboCambios = true;
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

        // 1. Cachés de Laravel en current (si aplica)
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + current + "/artisan && echo SI", 15);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            ctx.log("  Limpiando y regenerando cachés de Laravel en current…");
            ctx.ejecutar("cd " + current
                    + " && sudo -u www-data php artisan view:clear >/dev/null 2>&1 || true"
                    + " && cd " + current + " && sudo -u www-data php artisan config:clear >/dev/null 2>&1 || true"
                    + " && cd " + current + " && sudo -u www-data php artisan route:clear >/dev/null 2>&1 || true"
                    + " && cd " + current + " && sudo -u www-data php artisan config:cache >/dev/null 2>&1 || true"
                    + " && cd " + current + " && sudo -u www-data php artisan route:cache >/dev/null 2>&1 || true"
                    + " && cd " + current + " && sudo -u www-data php artisan view:cache >/dev/null 2>&1 || true"
                    + " && cd " + current + " && sudo -u www-data php artisan queue:restart >/dev/null 2>&1 || true", 180);
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
     * Comprueba respuesta HTTP y HTTPS reales y ejecuta una sonda física (Canary Probe)
     * dentro de {@code current/public} para garantizar que Nginx sirve exactamente
     * la nueva release desplegada.
     */
    static String validarHttpConCanary(DeploymentContext ctx, NginxEstado estado, String objetivoSha) {
        String dominio = ctx.institucion.dominioPrincipal();
        String currentPublic = ctx.rutaReleaseActual() + "/public";
        String tmpHtml = "/tmp/vpspilot-upd-val-" + ctx.institucion.getSlug() + ".html";
        String tokenName = "vpspilot-probe-" + System.currentTimeMillis() + ".txt";
        String probeFile = null;

        try {
            // 1. Prueba de la página principal HTTP (siguiendo redirect local si existe)
            CommandResult r = ctx.ejecutar(
                    "curl -skL -m 25 --resolve " + dominio + ":80:127.0.0.1 --resolve " + dominio + ":443:127.0.0.1"
                            + " -o " + tmpHtml + " -w '%{http_code}' http://" + dominio + "/", 90);
            String codigo = r.primeraLinea().trim();
            int st = (int) aEntero(codigo);
            if (st >= 500 || st == 0) {
                return "GET / responde HTTP " + (codigo.isBlank() ? "(sin respuesta)" : codigo);
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
                    // Verificar solo si algún bloque explícito incluye 443
                    check443 = estado.tieneHttpsConfigurado();
                }
            }
            if (check443) {
                CommandResult rSsl = ctx.ejecutar(
                        "curl -skL -m 25 --resolve " + dominio + ":443:127.0.0.1"
                                + " -o " + tmpHtml + " -w '%{http_code}' https://" + dominio + "/", 90);
                int stSsl = (int) aEntero(rSsl.primeraLinea().trim());
                if (stSsl >= 500 || stSsl == 0) {
                    return "GET HTTPS / responde HTTP " + rSsl.primeraLinea().trim();
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
            return null;
        } catch (StageException e) {
            return "no se pudo comprobar el sitio: " + e.getMessage();
        } finally {
            try {
                if (probeFile != null) {
                    ctx.ejecutar("rm -f " + probeFile, 20);
                }
                ctx.ejecutar("rm -f " + tmpHtml, 20);
            } catch (StageException ignored) {
            }
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
            ctx.ejecutar(ctx.sudo("cp -a \"" + backup + "\" \"" + vhostFile + "\" && rm -f \"" + backup + "\""), 30);
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo actualizar 'root' en " + vhostFile + ": " + res.salida().trim());
        }

        CommandResult test = ctx.ejecutar(ctx.sudo("nginx -t"), 45);
        if (!test.exito()) {
            ctx.ejecutar(ctx.sudo("cp -a \"" + backup + "\" \"" + vhostFile + "\" && rm -f \"" + backup + "\""), 30);
            throw new StageException(StageException.Tipo.FATAL,
                    "Al actualizar 'root' en " + vhostFile + " falló 'nginx -t' (revertido): " + test.salida().trim());
        }
        ctx.ejecutar(ctx.sudo("rm -f \"" + backup + "\""), 20);
        ctx.ok("Nginx actualizado (" + vhostFile + "): root → " + nuevoRoot);
    }

    private static void modernizarCacheStaticEnVhost(DeploymentContext ctx, String vhostFile) throws StageException {
        String backup = vhostFile + ".vpspilot.bak";
        ctx.ejecutar(ctx.sudo("cp -a \"" + vhostFile + "\" \"" + backup + "\""), 20);
        String pyCache = """
                import re, sys
                path = sys.argv[1]
                with open(path, 'r', encoding='utf-8') as f:
                    txt = f.read()
                old = 'location ~ \\\\.(js|css|png|jpg|jpeg|gif|ico|svg|webp|woff|woff2|ttf|eot|pdf)$ {\\n        expires 7d;\\n        access_log off;\\n        try_files $uri =404;\\n    }'
                new = '''location ~* \\\\.(?:html|htm)$ {
                        add_header Cache-Control "no-cache, must-revalidate" always;
                        add_header X-Frame-Options "SAMEORIGIN" always;
                        add_header X-Content-Type-Options "nosniff" always;
                        add_header Referrer-Policy "same-origin" always;
                        try_files $uri $uri/ /index.php?$query_string /index.html;
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
                pat = re.compile(r'location\\s*~\\s*.{1,3}\\(js\\|css\\|png\\|[^)]+\\)\\$\\s*\\{[^}]*expires\\s+7d;[^}]*\\}')
                if pat.search(txt):
                    txt = pat.sub(lambda _: new, txt)
                    with open(path, 'w', encoding='utf-8') as f:
                        f.write(txt)
                """;
        String b64 = Base64.getEncoder().encodeToString(pyCache.getBytes(StandardCharsets.UTF_8));
        String tmpPy = "/tmp/vpspilot-ngx-cache-" + ctx.institucion.getSlug() + ".py";
        ctx.ejecutar("echo " + b64 + " | base64 -d > " + tmpPy + " && "
                + ctx.sudo("python3 " + tmpPy + " \"" + vhostFile + "\"") + " ; rm -f " + tmpPy, 30);
        CommandResult test = ctx.ejecutar(ctx.sudo("nginx -t"), 30);
        if (!test.exito()) {
            ctx.ejecutar(ctx.sudo("cp -a \"" + backup + "\" \"" + vhostFile + "\""), 20);
        }
        ctx.ejecutar(ctx.sudo("rm -f \"" + backup + "\""), 20);
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
