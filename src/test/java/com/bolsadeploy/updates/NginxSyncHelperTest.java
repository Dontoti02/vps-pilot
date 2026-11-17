package com.bolsadeploy.updates;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class NginxSyncHelperTest {

    private static DeploymentContext contextoStub(Function<String, CommandResult> handler) {
        VpsServer vps = new VpsServer();
        vps.setNombre("VPS Test");
        vps.setHost("192.0.2.1");

        Institution inst = new Institution();
        inst.setNombre("Proyecto Demo");
        inst.setSlug("demo");
        inst.setDominio("demo.ejemplo.com");

        AppSettings ajustes = new AppSettings();
        ajustes.setRutaInstalacionBase("/var/www/bolsadeploy");

        return new DeploymentContext(vps, inst, ajustes, null, null) {
            @Override
            public CommandResult ejecutar(String comando, int timeoutSeg) {
                return handler.apply(comando);
            }
        };
    }

    @Test
    void rutaBaseRespetaRutaPersonalizadaYNormalizaRaicesComunes() {
        DeploymentContext ctx = contextoStub(cmd -> new CommandResult(cmd, 0, "", ""));
        assertEquals("/var/www/bolsadeploy/demo", ctx.rutaBase());

        ctx.institucion.setRutaInstalacion("/var/www/mi-sitio/");
        assertEquals("/var/www/mi-sitio", ctx.rutaBase());

        ctx.institucion.setRutaInstalacion("/var/www/");
        assertEquals("/var/www/demo", ctx.rutaBase());
    }

    @Test
    void validarCanaryDetectaMismatchCuandoNginxSirveOtraCarpeta() {
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.startsWith("curl -skL") && cmd.contains("-w '%{http_code}'")) {
                return new CommandResult(cmd, 0, "200", "");
            }
            if (cmd.startsWith("grep -cE 'Whoops")) {
                return new CommandResult(cmd, 0, "0\n1200", "");
            }
            if (cmd.startsWith("readlink -f ")) {
                return new CommandResult(cmd, 0, "/var/www/bolsadeploy/demo/releases/abc12345/public", "");
            }
            if (cmd.contains("curl -skL -m 15")) {
                return new CommandResult(cmd, 0, "<html>404 old site</html>", "");
            }
            if (cmd.startsWith("systemctl is-active nginx")) {
                return new CommandResult(cmd, 0, "active", "");
            }
            if (cmd.contains("python3")) {
                return new CommandResult(cmd, 0,
                        "BLOCK|EXPLICIT|/etc/nginx/sites-enabled/old.conf|80|/var/www/viejo|NO|NO|0|demo.ejemplo.com", "");
            }
            return new CommandResult(cmd, 0, "", "");
        });

        var estado = new NginxSyncHelper.NginxEstado(true, false,
                List.of(new NginxSyncHelper.NginxBlock(true, "/etc/nginx/sites-enabled/old.conf",
                        "80", "/var/www/viejo", false, false, 0, "demo.ejemplo.com")),
                List.of());

        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, estado, "abc12345");
        assertNotNull(fallo);
        assertTrue(fallo.startsWith("NGINX_MISMATCH:"),
                "Debe reportar NGINX_MISMATCH cuando la sonda devuelve contenido distinto: " + fallo);
    }

    @Test
    void validarCanaryApruebaCuandoNginxRespondeElSha() {
        String sha = "ad268b991234567890abcdef";
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.startsWith("curl -skL") && cmd.contains("-w '%{http_code}'")) {
                return new CommandResult(cmd, 0, "200", "");
            }
            if (cmd.startsWith("grep -cE 'Whoops")) {
                return new CommandResult(cmd, 0, "0\n1200", "");
            }
            if (cmd.startsWith("readlink -f ")) {
                return new CommandResult(cmd, 0, "/var/www/bolsadeploy/demo/releases/" + sha + "/public", "");
            }
            if (cmd.contains("curl -skL -m 15") || cmd.contains("curl -sk -m 15")) {
                return new CommandResult(cmd, 0, sha, "");
            }
            return new CommandResult(cmd, 0, "", "");
        });

        var estado = new NginxSyncHelper.NginxEstado(true, false,
                List.of(new NginxSyncHelper.NginxBlock(true, "/etc/nginx/sites-enabled/bolsa-demo.conf",
                        "80,443 ssl", "/var/www/bolsadeploy/demo/current/public", false, false, 0, "demo.ejemplo.com")),
                List.of());

        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, estado, sha);
        assertNull(fallo, "No debe haber fallo cuando el canary devuelve el SHA desplegado");
    }
}
