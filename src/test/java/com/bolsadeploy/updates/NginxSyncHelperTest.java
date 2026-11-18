package com.bolsadeploy.updates;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void validarDiagnosticaCausaRealEnHttp500() {
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.startsWith("curl -skL") && cmd.contains("-w '%{http_code}'")) {
                return new CommandResult(cmd, 0, "500", "");
            }
            if (cmd.startsWith("NGX=$(")) {
                return new CommandResult(cmd, 0,
                        "NGX: rewrite or internal redirection cycle\nLAR: local.ERROR: No application encryption key", "");
            }
            return new CommandResult(cmd, 0, "", "");
        });

        var estado = new NginxSyncHelper.NginxEstado(true, false, List.of(), List.of());
        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, estado, "51437834");
        assertNotNull(fallo);
        assertTrue(fallo.contains("HTTP 500"));
        assertTrue(fallo.contains("No application encryption key") || fallo.contains("redirection cycle"),
                "Debe incluir la causa real extraída de logs: " + fallo);
    }

    @Test
    void validarDetectaFaltaManifiestoViteEnLaravel() {
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("vite.config.*") && cmd.contains("manifest.json")) {
                return new CommandResult(cmd, 0, "FALTA", "");
            }
            return new CommandResult(cmd, 0, "", "");
        });
        var estado = new NginxSyncHelper.NginxEstado(true, false, List.of(), List.of());
        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, estado, "51437834");
        assertNotNull(fallo);
        assertTrue(fallo.contains("manifest.json"), "Debe avisar que falta public/build/manifest.json: " + fallo);
    }

    @Test
    void validarDetectaHttp500EnRutaLoginAunqueHomeDe200() {
        String sha = "51437834abcdef1234567890";
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("/login") && cmd.contains("-w '%{http_code}'")) {
                return new CommandResult(cmd, 0, "500", "");
            }
            if (cmd.startsWith("curl -skL") && cmd.contains("-w '%{http_code}'")) {
                return new CommandResult(cmd, 0, "200", "");
            }
            if (cmd.startsWith("grep -cE 'Whoops")) {
                return new CommandResult(cmd, 0, "0\n1200", "");
            }
            if (cmd.startsWith("readlink -f ")) {
                return new CommandResult(cmd, 0, "/var/www/bolsadeploy/demo/releases/" + sha + "/public", "");
            }
            if (cmd.contains("curl -skL -m 15") && !cmd.contains("/up")) {
                return new CommandResult(cmd, 0, sha, "");
            }
            if (cmd.contains("/artisan && echo SI")) {
                return new CommandResult(cmd, 0, "SI", "");
            }
            if (cmd.startsWith("NGX=$(")) {
                return new CommandResult(cmd, 0,
                        "LAR: production.ERROR: Vite manifest not found at: /var/www/bolsadeploy/demo/current/public/build/manifest.json", "");
            }
            return new CommandResult(cmd, 0, "", "");
        });

        var estado = new NginxSyncHelper.NginxEstado(true, false,
                List.of(new NginxSyncHelper.NginxBlock(true, "/etc/nginx/sites-enabled/bolsa-demo.conf",
                        "80", "/var/www/bolsadeploy/demo/current/public", false, false, 0, "demo.ejemplo.com")),
                List.of());

        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, estado, sha);
        assertNotNull(fallo);
        assertTrue(fallo.contains("GET /login responde HTTP 500"), "Debe detectar HTTP 500 en /login: " + fallo);
        assertTrue(fallo.contains("Vite manifest not found"), "Debe mostrar la causa real de laravel.log: " + fallo);
    }

    @Test
    void validarDetectaExcepcionNuevaEnLaravelLog() {
        String sha = "51437834abcdef1234567890";
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.startsWith("wc -l < ")) {
                return new CommandResult(cmd, 0, "40", "");
            }
            if (cmd.startsWith("curl -skL") && cmd.contains("-w '%{http_code}'")) {
                return new CommandResult(cmd, 0, "200", "");
            }
            if (cmd.startsWith("grep -cE 'Whoops")) {
                return new CommandResult(cmd, 0, "0\n1200", "");
            }
            if (cmd.startsWith("readlink -f ")) {
                return new CommandResult(cmd, 0, "/var/www/bolsadeploy/demo/releases/" + sha + "/public", "");
            }
            if (cmd.contains("curl -skL -m 15") && !cmd.contains("/up")) {
                return new CommandResult(cmd, 0, sha, "");
            }
            if (cmd.contains("/artisan && echo SI")) {
                return new CommandResult(cmd, 0, "SI", "");
            }
            if (cmd.contains("tail -n +41") && cmd.contains("ViteManifestNotFoundException")) {
                return new CommandResult(cmd, 0,
                        "[2026-11-18] production.ERROR: SQLSTATE[42S22]: Column not found: 1054 Unknown column 'vacancies'", "");
            }
            return new CommandResult(cmd, 0, "", "");
        });

        var estado = new NginxSyncHelper.NginxEstado(true, false,
                List.of(new NginxSyncHelper.NginxBlock(true, "/etc/nginx/sites-enabled/bolsa-demo.conf",
                        "80", "/var/www/bolsadeploy/demo/current/public", false, false, 0, "demo.ejemplo.com")),
                List.of());

        String fallo = NginxSyncHelper.validarHttpConCanary(ctx, estado, sha);
        assertNotNull(fallo);
        assertTrue(fallo.contains("SQLSTATE[42S22]"), "Debe detectar nueva excepción en laravel.log: " + fallo);
    }

    @Test
    void scriptModernizarEliminaBucleTryFilesYAppFallbackYActualizaCache() throws Exception {
        String confConBucle = """
                # Generado por BolsaDeploy Manager — institución: demo
                server {
                    listen 80;
                    server_name demo.ejemplo.com;
                    root /var/www/bolsadeploy/demo/current/public;

                    location / {
                        try_files $uri $uri/ /index.php?$query_string /index.html;
                    }

                    location ~* \\.(?:html|htm)$ {
                        add_header Cache-Control "no-cache, must-revalidate" always;
                        try_files $uri $uri/ /index.php?$query_string /index.html;
                    }

                    location ~ \\.(js|css|png|jpg|jpeg|gif|ico|svg|webp|woff|woff2|ttf|eot|pdf)$ {
                        expires 7d;
                        access_log off;
                        try_files $uri =404;
                    }
                }
                """;
        String confConAppFallback = """
                # Generado por BolsaDeploy Manager — institución: demo
                server {
                    listen 80;
                    server_name demo.ejemplo.com;
                    root /var/www/bolsadeploy/demo/current/public;

                    location / {
                        try_files $uri $uri/ @app_fallback;
                    }

                    location @app_fallback {
                        if (-f $document_root/index.php) {
                            rewrite ^ /index.php last;
                        }
                        add_header Cache-Control "no-cache, must-revalidate" always;
                        try_files /index.html =404;
                    }

                    location ~* \\.(?:html|htm)$ {
                        add_header Cache-Control "no-cache, must-revalidate" always;
                        try_files $uri @app_fallback;
                    }
                }
                """;
        Path pyPhp = Files.createTempFile("ngx-mod-php-", ".py");
        Path pySpa = Files.createTempFile("ngx-mod-spa-", ".py");
        Path conf1 = Files.createTempFile("ngx-conf1-", ".conf");
        Path conf2 = Files.createTempFile("ngx-conf2-", ".conf");
        Path conf3 = Files.createTempFile("ngx-conf3-", ".conf");
        try {
            Files.writeString(pyPhp, NginxSyncHelper.buildModernizeCacheScript(true));
            Files.writeString(pySpa, NginxSyncHelper.buildModernizeCacheScript(false));
            Files.writeString(conf1, confConBucle);
            Files.writeString(conf2, confConAppFallback);
            Files.writeString(conf3, confConAppFallback);

            Process p1 = new ProcessBuilder("python", pyPhp.toString(), conf1.toString()).start();
            assertEquals(0, p1.waitFor(), new String(p1.getErrorStream().readAllBytes()));
            String res1 = Files.readString(conf1);
            assertFalse(res1.contains("/index.php?$query_string /index.html;"));
            assertTrue(res1.contains("try_files $uri $uri/ /index.php?$query_string;"));
            assertTrue(res1.contains("try_files $uri =404;"));
            assertTrue(res1.contains("max-age=0, must-revalidate"));

            Process p2 = new ProcessBuilder("python", pyPhp.toString(), conf2.toString()).start();
            assertEquals(0, p2.waitFor(), new String(p2.getErrorStream().readAllBytes()));
            String res2 = Files.readString(conf2);
            assertFalse(res2.contains("@app_fallback"), "Debe remover por completo @app_fallback: " + res2);
            assertFalse(res2.contains("if (-f $document_root/index.php)"), "No debe dejar restos del bloque if: " + res2);
            assertTrue(res2.contains("try_files $uri $uri/ /index.php?$query_string;"));
            assertTrue(res2.contains("try_files $uri =404;"));

            Process p3 = new ProcessBuilder("python", pySpa.toString(), conf3.toString()).start();
            assertEquals(0, p3.waitFor(), new String(p3.getErrorStream().readAllBytes()));
            String res3 = Files.readString(conf3);
            assertFalse(res3.contains("@app_fallback"));
            assertTrue(res3.contains("try_files $uri $uri/ /index.html;"));
            assertTrue(res3.contains("try_files $uri /index.html =404;"));
        } finally {
            Files.deleteIfExists(pyPhp);
            Files.deleteIfExists(pySpa);
            Files.deleteIfExists(conf1);
            Files.deleteIfExists(conf2);
            Files.deleteIfExists(conf3);
        }
    }
}
