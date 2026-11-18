package com.bolsadeploy.reports;

import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.reports.DocumentacionPdfGenerator.DatosDocumentacion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas integrales del generador de documentación PDF (estructura, WinAnsi, Navicat y privacidad). */
class DocumentacionPdfGeneratorTest {

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    private static DatosDocumentacion datosMysqlEjemplo() {
        return new DatosDocumentacion(
                "IESTP Carlos Cueto Fernandini — Bolsa Laboral",
                "cueto",
                "bolsa.cueto.edu.pe",
                "https://bolsa.cueto.edu.pe",
                "https://bolsa.cueto.edu.pe/login",
                "https://bolsa.cueto.edu.pe/admin/dashboard",
                true,
                "Dontoti02/bolsalaboralv2",
                "main",
                "51437834",
                "admin@cueto.edu.pe",
                "Adm(2026)\\#Seguro!ñ",
                true,
                "MYSQL",
                "127.0.0.1",
                3306,
                "db_bolsa_cueto",
                "usr_cueto",
                "Db(Pass)\\$99!",
                true,
                "VPS Producción Lima 01",
                "144.202.88.10",
                22,
                "root",
                AuthType.PASSWORD,
                "Ssh#Pass(VPS)2026",
                true,
                "/var/www/bolsadeploy/cueto",
                "Horario de soporte técnico: Lunes a Viernes de 8:00 a 18:00.\nContacto: soporte@cueto.edu.pe",
                "18/11/2026 18:20"
        );
    }

    @Test
    void generaPdfValidoConEnlacesClicablesNavicatYTildes() throws Exception {
        byte[] pdf = DocumentacionPdfGenerator.generarBytes(datosMysqlEjemplo());
        String rawAscii = new String(pdf, StandardCharsets.ISO_8859_1);

        assertTrue(rawAscii.startsWith("%PDF-1.4\n"), "Debe iniciar con cabecera PDF 1.4");
        assertTrue(rawAscii.contains("\nxref\n"), "Debe contener tabla xref");
        assertTrue(rawAscii.contains("%%EOF"), "Debe finalizar con %%EOF");
        assertTrue(rawAscii.contains("/Encoding /WinAnsiEncoding"), "Debe declarar WinAnsiEncoding");

        // Verificar enlaces clicables URI
        assertTrue(rawAscii.contains("/Subtype /Link"));
        assertTrue(rawAscii.contains("/URI (https://bolsa.cueto.edu.pe)"));
        assertTrue(rawAscii.contains("/URI (https://bolsa.cueto.edu.pe/login)"));
        assertTrue(rawAscii.contains("/URI (https://bolsa.cueto.edu.pe/admin/dashboard)"));

        // Descomprimir los streams FlateDecode y comprobar contenido real
        String contenido = extraerTextoStreams(pdf);
        assertTrue(contenido.contains("Enlaces de Acceso al Sistema"), "Falta sección de enlaces");
        assertTrue(contenido.contains("Credenciales de Administrador"), "Falta sección de admin");
        assertTrue(contenido.contains("Navicat"), "Falta guía Navicat");
        assertTrue(contenido.contains("127.0.0.1"), "Debe especificar 127.0.0.1 en pestaña General");
        assertTrue(contenido.contains("3306"), "Debe incluir puerto MySQL");
        assertTrue(contenido.contains("db_bolsa_cueto"), "Debe incluir nombre de BD");
        assertTrue(contenido.contains("144.202.88.10"), "Debe incluir IP VPS para túnel SSH");
        assertTrue(contenido.contains("admin@cueto.edu.pe"), "Debe incluir correo admin");
        // Verificar que los caracteres especiales en contraseñas están correctamente escapados
        assertTrue(contenido.contains("Adm\\(2026\\)\\\\#Seguro!ñ"), "Contraseña admin escapada incorrectamente");
        assertTrue(contenido.contains("Db\\(Pass\\)\\\\$99!"), "Contraseña BD escapada incorrectamente");
        assertTrue(contenido.contains("Página 1 de "), "Debe incluir numeración de páginas");

        // Verificar que guardar a disco funciona correctamente
        Path tmp = Files.createTempFile("doc-pdf-test-", ".pdf");
        try {
            DocumentacionPdfGenerator.generar(datosMysqlEjemplo(), tmp);
            assertTrue(Files.size(tmp) > 2000, "El PDF debe tener contenido real");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void ocultaContrasenasCuandoUsuarioDesmarcaCasillas() throws Exception {
        DatosDocumentacion d = new DatosDocumentacion(
                "Institución Privada", "priv", "priv.ejemplo.com",
                "https://priv.ejemplo.com", "https://priv.ejemplo.com/login", "",
                true, "", "main", "abcdef12",
                "admin@priv.com", "SECRETO_ADMIN_NO_MOSTRAR", false,
                "POSTGRESQL", "127.0.0.1", 5432, "db_priv", "pg_user", "SECRETO_BD_NO_MOSTRAR", false,
                "VPS PG", "198.51.100.7", 2222, "ubuntu", AuthType.KEY, "SECRETO_SSH_NO_MOSTRAR", false,
                "/var/www/bolsadeploy/priv", "", "18/11/2026 18:25"
        );

        byte[] pdf = DocumentacionPdfGenerator.generarBytes(d);
        String contenido = extraerTextoStreams(pdf);

        assertFalse(contenido.contains("SECRETO_ADMIN_NO_MOSTRAR"), "No debe incluir contraseña admin si se desmarcó");
        assertFalse(contenido.contains("SECRETO_BD_NO_MOSTRAR"), "No debe incluir contraseña BD si se desmarcó");
        assertFalse(contenido.contains("SECRETO_SSH_NO_MOSTRAR"), "No debe incluir secreto SSH si se desmarcó");
        assertTrue(contenido.contains("PostgreSQL"), "Debe adaptarse al motor PostgreSQL");
        assertTrue(contenido.contains("5432"), "Debe mostrar puerto 5432");
        assertTrue(contenido.contains("Clave pública"), "Debe reflejar autenticación por llave SSH");
    }

    @Test
    void soportaMotoresSqliteYNingunaSinErrores() throws Exception {
        DatosDocumentacion sqlite = new DatosDocumentacion(
                "App SQLite", "sqlite-app", "sqlite.ejemplo.com",
                "http://sqlite.ejemplo.com", "", "",
                false, "", "main", "11223344",
                "admin@sqlite.com", "pass", true,
                "SQLITE", "127.0.0.1", 0, "database.sqlite", "", "", false,
                "VPS Mini", "203.0.113.5", 22, "root", AuthType.PASSWORD, "", false,
                "/var/www/bolsadeploy/sqlite-app", "", "18/11/2026 18:25"
        );
        String txtSqlite = extraerTextoStreams(DocumentacionPdfGenerator.generarBytes(sqlite));
        assertTrue(txtSqlite.contains("SQLite"));
        assertTrue(txtSqlite.contains("database.sqlite"));

        DatosDocumentacion ninguna = new DatosDocumentacion(
                "Landing Estática", "landing", "landing.ejemplo.com",
                "https://landing.ejemplo.com", "", "",
                true, "", "main", "55667788",
                "", "", false,
                "NINGUNA", "", 0, "", "", "", false,
                "VPS Web", "203.0.113.9", 22, "root", AuthType.PASSWORD, "", false,
                "/var/www/bolsadeploy/landing", "", "18/11/2026 18:25"
        );
        String txtNinguna = extraerTextoStreams(DocumentacionPdfGenerator.generarBytes(ninguna));
        assertTrue(txtNinguna.contains("Sin base de datos"));
    }

    @Test
    void helperGeneraPasswordSeguraYSincronizaYAplicaAdminConSintaxisPhpValida() throws Exception {
        String pwd = VpsDocumentationHelper.generarPasswordAdminAmigable("iespiberia");
        assertTrue(pwd.length() >= 12, "Debe tener al menos 12 caracteres: " + pwd);
        assertTrue(pwd.startsWith("Iespib#"), "Debe usar prefijo legible del slug: " + pwd);
        assertTrue(pwd.endsWith("!"));

        // Verificar sintaxis PHP real con `php -l`
        String readPhp = VpsDocumentationHelper.buildReadAdminPhpScript();
        String setPhp = VpsDocumentationHelper.buildSetAdminPasswordPhpScript(
                "admin@iespiberia.edu.pe", "Iespib#48Kmx9p!", "Admin Iberia");
        assertFalse(setPhp.contains("Iespib#48Kmx9p!"),
                "La contraseña debe viajar codificada en Base64 para evitar problemas con $ en bash");

        Path f1 = Files.createTempFile("read-admin-", ".php");
        Path f2 = Files.createTempFile("set-admin-", ".php");
        try {
            Files.writeString(f1, readPhp);
            Files.writeString(f2, setPhp);
            for (Path f : List.of(f1, f2)) {
                try {
                    Process p = new ProcessBuilder("php", "-l", f.toString()).redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes());
                    assertEquals(0, p.waitFor(), "Error de sintaxis PHP en " + f + ": " + out);
                } catch (java.io.IOException ignored) {
                }
            }
        } finally {
            Files.deleteIfExists(f1);
            Files.deleteIfExists(f2);
        }

        // Probar lectura + actualización contra stub de DeploymentContext
        var vps = new com.bolsadeploy.domain.VpsServer();
        vps.setNombre("VPS Iberia");
        vps.setHost("198.51.100.10");
        var inst = new com.bolsadeploy.domain.Institution();
        inst.setNombre("IESP Iberia");
        inst.setSlug("iespiberia");
        inst.setDominio("bolsalaboral.iespiberia.edu.pe");
        var ajustes = new com.bolsadeploy.domain.AppSettings();
        ajustes.setRutaInstalacionBase("/var/www/bolsadeploy");

        var ctx = new com.bolsadeploy.deployment.DeploymentContext(vps, inst, ajustes, null, null) {
            @Override
            public com.bolsadeploy.infrastructure.ssh.CommandResult ejecutar(String cmd, int timeoutSeg) {
                if (cmd.contains("DB_CONNECTION|DB_HOST")) {
                    return new com.bolsadeploy.infrastructure.ssh.CommandResult(cmd, 0,
                            "APP_URL=https://bolsalaboral.iespiberia.edu.pe\nDB_CONNECTION=mysql\nDB_DATABASE=db_iberia\nDB_USERNAME=u_iberia\nDB_PASSWORD=secret", "");
                }
                if (cmd.contains("/artisan && test -f")) {
                    return new com.bolsadeploy.infrastructure.ssh.CommandResult(cmd, 0, "SI", "");
                }
                if (cmd.contains("vpspilot-read-admin-")) {
                    return new com.bolsadeploy.infrastructure.ssh.CommandResult(cmd, 0,
                            "ADMIN_TABLE=user\nADMIN_EMAIL=admin@iespiberia.edu.pe", "");
                }
                if (cmd.contains("vpspilot-set-admin-")) {
                    return new com.bolsadeploy.infrastructure.ssh.CommandResult(cmd, 0,
                            "ADMIN_OK|UPDATED|user|admin@iespiberia.edu.pe", "");
                }
                return new com.bolsadeploy.infrastructure.ssh.CommandResult(cmd, 0, "", "");
            }
        };

        var det = VpsDocumentationHelper.leerDatosRealesDesdeVps(ctx);
        assertTrue(det.httpsActivo(), "Debe detectar HTTPS desde APP_URL");
        assertEquals("admin@iespiberia.edu.pe", det.adminEmail());
        assertEquals("db_iberia", det.env().get("DB_DATABASE"));

        var res = VpsDocumentationHelper.aplicarCredencialesAdminEnVps(ctx,
                "admin@iespiberia.edu.pe", "Iespib#48Kmx9p!", "Admin Iberia");
        assertTrue(res.exito());
        assertEquals("admin@iespiberia.edu.pe", res.emailAplicado());
        assertEquals("UPDATED", res.accion());
    }

    private static String extraerTextoStreams(byte[] pdf) throws Exception {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        List<String> partes = new ArrayList<>();
        int idx = 0;
        while (true) {
            int start = raw.indexOf("stream\n", idx);
            if (start < 0) break;
            start += "stream\n".length();
            int end = raw.indexOf("\nendstream", start);
            if (end < 0) break;
            byte[] compressed = new byte[end - start];
            System.arraycopy(pdf, start, compressed, 0, compressed.length);

            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) break;
                baos.write(buf, 0, n);
            }
            inflater.end();
            partes.add(new String(baos.toByteArray(), WIN_ANSI));
            idx = end + "\nendstream".length();
        }
        return String.join("\n", partes);
    }
}
