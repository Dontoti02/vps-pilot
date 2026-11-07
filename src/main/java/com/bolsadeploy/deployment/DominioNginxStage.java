package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.security.CommandValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Etapa 4 — Dominio y vhost de Nginx.
 *
 * Comprueba el DNS real del dominio, genera el bloque {@code server} con
 * {@link NginxConfigBuilder} y lo instala como archivo <b>propio</b> de la
 * institución ({@code bolsa-<slug>.conf}), validando con {@code nginx -t}
 * ANTES de dejarlo activo. Si la configuración fuera inválida se desactiva y
 * se explica el motivo: jamás se deja Nginx roto ni se tocan otros vhosts.
 */
public class DominioNginxStage implements DeploymentStage {

    @Override
    public String id() {
        return "dominio-nginx";
    }

    @Override
    public String nombre() {
        return "Dominio y vhost de Nginx";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        // Siempre se reescribe: es barato y mantiene el vhost sincronizado con
        // el dominio/socket actuales (una corrección del dominio se aplica ya).
        return false;
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String dominio = ctx.institucion.getDominio();
        try {
            CommandValidator.exigirDominio(dominio);
        } catch (IllegalArgumentException e) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, e.getMessage());
        }
        String slug = ctx.institucion.getSlug();

        // ---------------------------------------------------------------- DNS
        CommandResult dns = ctx.ejecutar(
                "getent ahosts " + dominio + " | awk '{print $1}' | sort -u | tr '\\n' ' '", 30);
        String ips = dns.salida().trim();
        boolean apunta = !ips.isBlank();
        ctx.valores.put("dnsApunta", String.valueOf(apunta));
        ctx.valores.put("dnsIps", ips);
        String ipServidor = ctx.valores.getOrDefault("ipPublica", "");
        if (apunta && !ipServidor.isBlank() && ips.contains(ipServidor)) {
            ctx.ok("DNS de " + dominio + " apunta al servidor (" + ipServidor + ")");
        } else if (apunta) {
            ctx.log("  [!] DNS de " + dominio + " resuelve a [" + ips.trim()
                    + "] pero la IP del servidor es '" + ipServidor
                    + "'. Se crea el sitio, pero el SSL se omitirá hasta corregir el registro A.");
        } else {
            ctx.log("  [!] " + dominio + " aún no resuelve (DNS pendiente). El sitio se crea igual; "
                    + "el SSL se omitirá hasta que el dominio apunte aquí.");
        }

        // ------------------------------------------------- Bloque server
        String principal = ctx.institucion.dominioPrincipal();
        String alias = dominio.startsWith("www.") ? dominio : "www." + dominio;
        String[] serverNames = principal.equals(alias)
                ? new String[]{principal}
                : new String[]{principal, alias};

        String rutaPublic = ctx.rutaReleaseActual() + "/public";
        String conf = NginxConfigBuilder.servidor(serverNames, rutaPublic, ctx.socketFpm(), slug, 16);
        ctx.log("  server_name: " + String.join(" ", serverNames));
        ctx.log("  root: " + rutaPublic);
        ctx.log("  fastcgi_pass: " + ctx.socketFpm());

        String nombreConf = "bolsa-" + slug + ".conf";
        String rutaDisponible = "/etc/nginx/sites-available/" + nombreConf;
        String rutaActivada = "/etc/nginx/sites-enabled/" + nombreConf;
        String tmp = "/tmp/bolsa-" + slug + ".nginx.conf";
        Path local = null;
        try {
            local = Files.createTempFile("bolsa-nginx-", ".conf");
            Files.writeString(local, conf, StandardCharsets.UTF_8);
            ctx.ssh.subir(local, tmp);

            ctx.exigirExito(ctx.sudo("install -m 644 " + tmp + " " + rutaDisponible), 60,
                    "Instalación del vhost " + nombreConf);
            ctx.ejecutar("rm -f " + tmp, 30);

            // Activar solo DESPUÉS de tener el archivo válido en sites-available
            ctx.exigirExito(ctx.sudo("ln -sfn " + rutaDisponible + " " + rutaActivada), 30,
                    "Activación del vhost " + nombreConf);

            CommandResult test = ctx.ejecutar(ctx.sudo("nginx -t"), 60);
            if (!test.exito()) {
                // Desactivar y comprobar que Nginx queda sano como estaba
                ctx.ejecutar(ctx.sudo("rm -f " + rutaActivada), 30);
                CommandResult testTras = ctx.ejecutar(ctx.sudo("nginx -t"), 60);
                throw new StageException(StageException.Tipo.FATAL,
                        "La configuración generada no pasa 'nginx -t' y fue DESACTIVADA para no "
                                + "dejar el servicio caído" + (testTras.exito() ? "" : " (Nginx quedó inválido)")
                                + ": " + limpiar(test.salida()));
            }
            ctx.ok("nginx -t correcto con el vhost " + nombreConf);

            // ------------------------------------------------- Recarga del servicio
            ctx.exigirExito(ctx.sudo("systemctl enable nginx >/dev/null 2>&1 || true; "
                    + "systemctl reload nginx 2>/dev/null || systemctl restart nginx"), 120,
                    "Recarga de Nginx");

            // ------------------------------------------------------- Verificación
            CommandResult confOk = ctx.ejecutar(
                    ctx.sudo("test -f " + rutaDisponible + " && test -L " + rutaActivada
                            + " && echo OK"), 30);
            if (!"OK".equals(confOk.primeraLinea().trim())) {
                throw new StageException(StageException.Tipo.FATAL,
                        "El vhost no quedó instalado/activado correctamente.");
            }
            CommandResult activo = ctx.ejecutar("systemctl is-active nginx", 30);
            if (!"active".equals(activo.primeraLinea().trim())) {
                throw new StageException(StageException.Tipo.FATAL,
                        "Nginx no quedó activo tras la recarga: " + limpiar(activo.salida()));
            }
            ctx.valores.put("nginxConf", rutaDisponible);
            ctx.ok("Sitio de " + dominio + " configurado en " + rutaDisponible);

        } catch (IOException e) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo generar el archivo de Nginx: " + e.getMessage(), e);
        } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "Fallo de conexión al subir la configuración: " + e.getMessage(), e);
        } finally {
            if (local != null) {
                try {
                    Files.deleteIfExists(local);
                } catch (IOException ignored) {
                }
            }
            ctx.ejecutar("rm -f " + tmp, 30);
        }
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 600 ? l.substring(0, 600) + "…" : l;
    }
}
