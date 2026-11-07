package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.security.CommandValidator;

/**
 * Etapa 10 — Certificado SSL (Let's Encrypt / Certbot).
 *
 * Comportamiento honesto:
 * <ul>
 *   <li>Si HTTPS no fue solicitado → etapa <b>OMITIDA</b> con motivo
 *       "HTTPS no solicitado" (nunca se registra como éxito).</li>
 *   <li>Si el DNS del dominio no apunta a la IP del servidor →
 *       <b>OMITIDA</b> con motivo "DNS pendiente" (Certbot fallaría igual y
 *       además no podría validar el dominio).</li>
 *   <li>Certbot se instala solo si hace falta, siempre en modo no interactivo
 *       con los términos aceptados y correo de contacto.</li>
 *   <li>Si Certbot falla de verdad, la etapa NO se marca exitosa: se registra
 *       {@code sslEstado=PENDIENTE: <motivo>} y el sitio queda funcionando en
 *       HTTP (la validación final lo reflejará como advertencia).</li>
 *   <li>Si termina bien, se verifica el archivo real del certificado y que
 *       https responda 200.</li>
 * </ul>
 */
public class SslStage implements DeploymentStage {

    @Override
    public String id() {
        return "ssl";
    }

    @Override
    public String nombre() {
        return "Certificado SSL (Let's Encrypt)";
    }

    @Override
    public String motivoOmitida(DeploymentContext ctx) {
        if (!ctx.instalarHttps) {
            return "HTTPS no solicitado";
        }
        if (!"true".equals(ctx.valores.getOrDefault("dnsApunta", "false"))) {
            return "DNS pendiente: el dominio no apunta a la IP del servidor";
        }
        String ip = ctx.valores.getOrDefault("ipPublica", "");
        String ips = ctx.valores.getOrDefault("dnsIps", "");
        if (!ip.isBlank() && !ips.isBlank() && !ips.contains(ip)) {
            return "DNS pendiente: el dominio resuelve a [" + ips.trim() + "] y la IP del servidor es " + ip;
        }
        return null;
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        try {
            String dominio = ctx.institucion.dominioPrincipal();
            CommandResult r = ctx.ejecutar(
                    "test -f /etc/letsencrypt/live/" + dominio + "/fullchain.pem && echo SI", 30);
            boolean cert = "SI".equals(r.primeraLinea().trim());
            ctx.valores.put("sslEstado", cert ? "OK" : ctx.valores.getOrDefault("sslEstado", ""));
            return cert;
        } catch (StageException e) {
            return false;
        }
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String dominio = ctx.institucion.dominioPrincipal();
        String alias = ctx.institucion.getDominio().startsWith("www.")
                ? ctx.institucion.getDominio()
                : "www." + ctx.institucion.getDominio();
        try {
            CommandValidator.exigirDominio(dominio);
        } catch (IllegalArgumentException e) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, e.getMessage());
        }

        // ------------------------------------------------------- Certbot
        String certbot = ctx.ejecutar("command -v certbot || true", 30).primeraLinea().trim();
        if (certbot.isBlank()) {
            ctx.log("  Instalando Certbot + plugin de Nginx…");
            CommandResult inst = ctx.ejecutar(ctx.sudo("export DEBIAN_FRONTEND=noninteractive "
                    + "&& apt-get install -y certbot python3-certbot-nginx"), 900);
            if (!inst.exito()) {
                pendiente(ctx, "no se pudo instalar Certbot: " + recortar(inst.salida()));
                return;
            }
        }

        // ------------------------------------------------- Contacto/condiciones
        String email = ctx.adminEmail != null && !ctx.adminEmail.isBlank()
                ? ctx.adminEmail
                : (ctx.institucion.getEmailContacto() != null ? ctx.institucion.getEmailContacto() : "");
        String registro = CommandValidator.esEmail(email)
                ? "-m " + CommandValidator.escaparShell(email)
                : "--register-unsafely-without-email";

        // --------------------------------------------- Emisión del certificado
        String dominios = "-d " + dominio + (alias.equals(dominio) ? "" : " -d " + alias);
        String cmd = "certbot certonly --nginx " + dominios
                + " --non-interactive --agree-tos " + registro
                + " --keep-until-expiring --expand";
        ctx.log("  Solicitando certificado para " + dominios.replace("-d ", "") + "…");
        CommandResult emision = ctx.ejecutar(ctx.sudo(cmd), 600);

        String rutaCert = "/etc/letsencrypt/live/" + dominio + "/fullchain.pem";
        CommandResult existeCert = ctx.ejecutar("test -f " + rutaCert + " && echo SI", 30);
        boolean certOk = "SI".equals(existeCert.primeraLinea().trim());

        if (!certOk || !emision.exito()) {
            pendiente(ctx, emision.exito()
                    ? "Certbot terminó sin crear " + rutaCert
                    : recortar(emision.salida()));
            return;
        }
        ctx.ok("Certificado emitido en " + rutaCert);

        // ------------------------------- Asegurar el redirect a HTTPS en Nginx
        CommandResult redirect = ctx.ejecutar(
                ctx.sudo("certbot install-only --nginx -d " + dominio
                        + (alias.equals(dominio) ? "" : " -d " + alias)
                        + " --non-interactive --redirect 2>/dev/null || true"), 300);
        CommandResult testNginx = ctx.ejecutar(ctx.sudo("nginx -t"), 60);
        if (!testNginx.exito()) {
            // La reescritura de certbot dejó la config mala: restaurar recargando
            // no es seguro; se informa y se comprueba que el servicio sigue vivo.
            ctx.log("  [!] nginx -t falló tras certbot: " + recortar(testNginx.salida()));
        }
        ctx.ejecutar(ctx.sudo("systemctl reload nginx 2>/dev/null || systemctl restart nginx"), 120);

        // ------------------------------------------- Verificación real (https)
        CommandResult https = ctx.ejecutar(
                "curl -sL -o /dev/null -m 20 -w '%{http_code}' https://" + dominio + "/", 60);
        String codigo = https.primeraLinea().trim();
        if (!"200".equals(codigo)) {
            pendiente(ctx, "el certificado existe pero https://" + dominio + "/ responde HTTP " + codigo);
            return;
        }

        CommandResult timer = ctx.ejecutar(
                "systemctl list-unit-files 2>/dev/null | grep -c '^certbot.timer'", 30);
        if ("1".equals(timer.primeraLinea().trim())) {
            CommandResult activo = ctx.ejecutar("systemctl is-active certbot.timer 2>/dev/null", 30);
            if (!"active".equals(activo.primeraLinea().trim())) {
                ctx.ejecutar(ctx.sudo("systemctl enable --now certbot.timer"), 120);
            }
            ctx.log("  Renovación automática habilitada (certbot.timer).");
        } else {
            ctx.log("  [!] No se detectó certbot.timer: programe la renovación manualmente.");
        }

        ctx.valores.put("sslEstado", "OK");
        ctx.valores.put("sslCertificado", rutaCert);
        ctx.ok("HTTPS activo y verificado (HTTP 200 sobre TLS) para " + dominio);
    }

    /** Registra un SSL pendiente como ADVERTENCIA real, nunca como éxito. */
    private void pendiente(DeploymentContext ctx, String motivo) {
        String texto = "PENDIENTE: " + motivo;
        ctx.valores.put("sslEstado", texto);
        ctx.log("  [!] HTTPS NO configurado — " + texto);
        ctx.log("  [!] El sitio sigue disponible por HTTP. Reintente esta etapa cuando el "
                + "dominio apunte al servidor o revise Certbot manualmente.");
    }

    private static String recortar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
