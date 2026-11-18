package com.bolsadeploy.deployment;

import com.bolsadeploy.domain.AppSettings;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de regresiones de producción (500 Server Error):
 * compilación Vite, cachés seguras con env(), verificación de BD y reconciliación de migraciones.
 */
class ProductionReadinessTest {

    private static DeploymentContext contextoStub(Function<String, CommandResult> handler, List<String> comandos) {
        VpsServer vps = new VpsServer();
        vps.setNombre("VPS Test");
        vps.setHost("192.0.2.1");

        Institution inst = new Institution();
        inst.setNombre("Bolsa Demo");
        inst.setSlug("demo");
        inst.setDominio("demo.ejemplo.com");
        inst.setTipoBaseDatos("MYSQL");
        inst.setNombreBaseDatos("db_demo");
        inst.setUsuarioBaseDatos("u_demo");

        AppSettings ajustes = new AppSettings();
        ajustes.setRutaInstalacionBase("/var/www/bolsadeploy");

        DeploymentContext ctx = new DeploymentContext(vps, inst, ajustes, null, null) {
            @Override
            public CommandResult ejecutar(String comando, int timeoutSeg) {
                if (comandos != null) {
                    comandos.add(comando);
                }
                return handler.apply(comando);
            }
        };
        ctx.valores.put("releasePath", "/var/www/bolsadeploy/demo/releases/abc12345");
        return ctx;
    }

    @Test
    void dependenciasAppUsaGrepSilenciosoYEjecutaNpmRunBuildYVerificaManifest() throws Exception {
        List<String> cmds = new ArrayList<>();
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("composer.json && echo SI")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("package.json && echo SI")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("command -v npm")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("grep -qE '\"build\"")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("vite.config.*")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("manifest.json && echo OK")) return new CommandResult(cmd, 0, "OK", "");
            if (cmd.contains("vendor/autoload.php && echo OK")) return new CommandResult(cmd, 0, "OK", "");
            if (cmd.contains("/public/index.php")) return new CommandResult(cmd, 0, "SI", "");
            return new CommandResult(cmd, 0, "", "");
        }, cmds);

        new DependenciasAppStage().ejecutar(ctx);

        assertTrue(cmds.stream().anyMatch(c -> c.contains("grep -qE '\"build\"")),
                "Debe usar grep -qE para que primeraLinea() sea 'SI'");
        assertTrue(cmds.stream().anyMatch(c -> c.contains("npm run build")),
                "Debe ejecutar npm run build cuando package.json tiene script build");
        assertTrue(cmds.stream().anyMatch(c -> c.contains("rm -f") && c.contains("/public/hot")),
                "Debe eliminar public/hot en producción");
        assertTrue(cmds.stream().anyMatch(c -> c.contains("public/build/manifest.json")),
                "Debe verificar físicamente public/build/manifest.json en proyectos Laravel + Vite");
    }

    @Test
    void dependenciasAppFallaSiFaltaManifestTrasNpmRunBuild() {
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("composer.json && echo SI")) return new CommandResult(cmd, 0, "", "");
            if (cmd.contains("package.json && echo SI")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("command -v npm")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("grep -qE '\"build\"")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("vite.config.*")) return new CommandResult(cmd, 0, "SI", "");
            if (cmd.contains("manifest.json && echo OK")) return new CommandResult(cmd, 0, "", "");
            return new CommandResult(cmd, 0, "", "");
        }, null);

        StageException ex = assertThrows(StageException.class, () -> new DependenciasAppStage().ejecutar(ctx));
        assertTrue(ex.getMessage().contains("manifest.json"),
                "Debe abortar antes de activar la release si falta manifest.json: " + ex.getMessage());
    }

    @Test
    void optimizarCachesOmiteConfigCacheSiAppUsaEnvFueraDeConfig() throws Exception {
        List<String> cmds = new ArrayList<>();
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("grep -rnE")) {
                return new CommandResult(cmd, 0, "app/Services/MailConfigService.php:51: env('MAIL_HOST')", "");
            }
            if (cmd.contains("route:cache")) {
                return new CommandResult(cmd, 1, "", "Unable to prepare route [x] for serialization. Uses Closure.");
            }
            return new CommandResult(cmd, 0, "", "");
        }, cmds);

        MigracionStage.optimizarCachesLaravel(ctx, "/var/www/bolsadeploy/demo/current");

        assertFalse(cmds.stream().anyMatch(c -> c.contains("php artisan config:cache")),
                "No debe ejecutar config:cache cuando hay env() en app/");
        assertTrue(cmds.stream().anyMatch(c -> c.contains("php artisan config:clear")));
        assertTrue(cmds.stream().anyMatch(c -> c.contains("php artisan route:clear")));
        assertTrue(cmds.stream().anyMatch(c -> c.contains("php artisan view:cache")),
                "Un fallo en route:cache no debe impedir ejecutar view:cache");
    }

    @Test
    void migracionReconciliaTablaPreexistenteYEjecutaNuevasMigraciones() throws Exception {
        AtomicInteger migAttempt = new AtomicInteger(0);
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("php artisan migrate --force")) {
                if (migAttempt.getAndIncrement() == 0) {
                    return new CommandResult(cmd, 1, "",
                            "SQLSTATE[42S01]: Base table or view already exists: 1050 Table 'cache' already exists");
                }
                return new CommandResult(cmd, 0, "2026_11_04_000001_add_vacancies ... DONE", "");
            }
            if (cmd.contains("vpspilot-migrec-")) {
                return new CommandResult(cmd, 0,
                        "RECONCILED:0001_01_01_000001_create_cache_table (tablas ya existentes: cache, cache_locks)", "");
            }
            return new CommandResult(cmd, 0, "", "");
        }, null);

        new MigracionStage().ejecutarMigracionesLaravel(ctx, "/var/www/bolsadeploy/demo/releases/abc12345");
        assertEquals(2, migAttempt.get(), "Debe reintentar migrate --force tras reconciliar la tabla preexistente");
    }

    @Test
    void migracionFallaFuerteAnteErrorSqlRealSinTragarselo() {
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("php artisan migrate --force")) {
                return new CommandResult(cmd, 1, "",
                        "SQLSTATE[42S02]: Base table or view not found: 1146 Table 'db_demo.missing' doesn't exist");
            }
            return new CommandResult(cmd, 0, "", "");
        }, null);

        StageException ex = assertThrows(StageException.class,
                () -> new MigracionStage().ejecutarMigracionesLaravel(ctx, "/var/www/bolsadeploy/demo/releases/abc12345"));
        assertTrue(ex.getMessage().contains("1146"), "Debe exponer el error SQL real: " + ex.getMessage());
    }

    @Test
    void verificarConexionBdReportaErrorSinExponerPassword() {
        DeploymentContext ctx = contextoStub(cmd -> {
            if (cmd.contains("vpspilot-dbcheck-")) {
                return new CommandResult(cmd, 1, "DB_ERR:mysql:SQLSTATE[HY000] [1045] Access denied for user 'u_demo'", "");
            }
            if (cmd.contains("awk -F=")) {
                return new CommandResult(cmd, 0, "DB_CONNECTION=mysql DB_HOST=127.0.0.1 DB_DATABASE=db_demo DB_PASSWORD=(VACÍA)", "");
            }
            return new CommandResult(cmd, 0, "", "");
        }, null);

        StageException ex = assertThrows(StageException.class,
                () -> new MigracionStage().verificarConexionBdLaravel(ctx,
                        "/var/www/bolsadeploy/demo/releases/abc12345",
                        "/var/www/bolsadeploy/demo/shared"));
        assertTrue(ex.getMessage().contains("Access denied"));
        assertTrue(ex.getMessage().contains("DB_PASSWORD=(VACÍA)"));
    }

    @Test
    void scriptsPhpDeMigracionSonValidosYNoContienenDestructivos() throws Exception {
        String dbCheck = MigracionStage.buildDbCheckScript();
        String rec = MigracionStage.buildReconcileMigrationScript();

        assertTrue(dbCheck.startsWith("<?php"));
        assertTrue(dbCheck.contains("getPdo()"));
        assertTrue(rec.startsWith("<?php"));
        assertTrue(rec.contains("Schema::hasTable"));
        assertTrue(rec.contains("Schema::hasColumn"));
        assertFalse(rec.contains("migrate:fresh"));
        assertFalse(rec.contains("db:wipe"));

        // Validar sintaxis PHP real con `php -l` si PHP está disponible en el equipo
        java.nio.file.Path f1 = java.nio.file.Files.createTempFile("dbcheck-", ".php");
        java.nio.file.Path f2 = java.nio.file.Files.createTempFile("migrec-", ".php");
        try {
            java.nio.file.Files.writeString(f1, dbCheck);
            java.nio.file.Files.writeString(f2, rec);
            for (java.nio.file.Path f : List.of(f1, f2)) {
                try {
                    Process p = new ProcessBuilder("php", "-l", f.toString()).redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes());
                    assertEquals(0, p.waitFor(), "Error de sintaxis PHP en " + f + ": " + out);
                } catch (java.io.IOException ignored) {
                    // PHP CLI no instalado en este entorno; omitir lint externo
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(f1);
            java.nio.file.Files.deleteIfExists(f2);
        }
    }
}
