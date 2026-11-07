package com.bolsadeploy.deployment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas del generador del .env de Laravel (puro, sin E/S). */
class EnvFileBuilderTest {

    private static final String EJEMPLO = """
            APP_NAME=Laravel
            APP_ENV=local
            APP_KEY=
            APP_DEBUG=false
            APP_URL=http://localhost

            # DB_HOST=127.0.0.1
            # DB_DATABASE=laravel
            DB_CONNECTION=sqlite
            """;

    @Test
    void reemplazaValorExistente() {
        String salida = EnvFileBuilder.parse(EJEMPLO)
                .poner("APP_ENV", "production")
                .aContenido();
        assertTrue(salida.contains("APP_ENV=production"));
        assertFalse(salida.contains("APP_ENV=local"));
        assertEquals(1, contar(salida, "APP_ENV="));
    }

    @Test
    void descomentaClaveComentada() {
        String salida = EnvFileBuilder.parse(EJEMPLO)
                .poner("DB_HOST", "localhost")
                .aContenido();
        assertTrue(salida.contains("\nDB_HOST=localhost"));
        assertFalse(salida.contains("# DB_HOST="));
    }

    @Test
    void anadeClaveNuevaSiNoEstaEnElEjemplo() {
        String salida = EnvFileBuilder.parse(EJEMPLO)
                .poner("SESSION_DRIVER", "file")
                .aContenido();
        assertTrue(salida.endsWith("SESSION_DRIVER=file\n"));
    }

    @Test
    void conservaComentariosYOrden() {
        String salida = EnvFileBuilder.parse(EJEMPLO).poner("APP_ENV", "production").aContenido();
        int idxNombre = salida.indexOf("APP_NAME=Laravel");
        int idxEnv = salida.indexOf("APP_ENV=production");
        assertTrue(idxNombre >= 0 && idxEnv > idxNombre, "el orden original se conserva");
        assertTrue(salida.contains("DB_CONNECTION=sqlite"));
    }

    @Test
    void valoresConEspaciosVanEntreComillas() {
        String salida = EnvFileBuilder.parse(EJEMPLO)
                .poner("APP_NAME", "Bolsa Laboral \"Demo\" #1")
                .aContenido();
        assertTrue(salida.contains("APP_NAME=\"Bolsa Laboral \\\"Demo\\\" #1\""));
    }

    @Test
    void valorSimpleSinComillas() {
        assertEquals("produccion", EnvFileBuilder.formatearValor("produccion"));
        assertEquals("/var/www/x", EnvFileBuilder.formatearValor("/var/www/x"));
        assertTrue(EnvFileBuilder.formatearValor("con espacio").startsWith("\""));
    }

    @Test
    void claveInvalidaRechazada() {
        assertThrows(IllegalArgumentException.class,
                () -> EnvFileBuilder.parse(EJEMPLO).poner("CLAVE=mal", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> EnvFileBuilder.parse(EJEMPLO).poner("  ", "x"));
    }

    @Test
    void ponerSiPresenteIgnoraVacios() {
        EnvFileBuilder b = EnvFileBuilder.parse(EJEMPLO).ponerSiPresente("MAIL_FROM", "  ");
        assertNull(b.obtener("MAIL_FROM"));
        b.ponerSiPresente("MAIL_FROM", "a@b.com");
        assertEquals("a@b.com", b.obtener("MAIL_FROM"));
    }

    @Test
    void ejemploOficialDelRepoSePuedeParsear() throws Exception {
        // El .env.example real debe ser compatible con el builder
        var recurso = getClass().getResourceAsStream("/db/ejemplo-env.txt");
        if (recurso == null) {
            return; // recurso de apoyo opcional
        }
        String contenido = new String(recurso.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        EnvFileBuilder b = EnvFileBuilder.parse(contenido);
        assertTrue(b.contieneEnEjemplo("APP_KEY"));
        String salida = b.poner("APP_KEY", "base64:abc").aContenido();
        assertTrue(salida.contains("APP_KEY=base64:abc"));
    }

    private static int contar(String texto, String fragmento) {
        int n = 0;
        for (int i = texto.indexOf(fragmento); i >= 0; i = texto.indexOf(fragmento, i + 1)) {
            n++;
        }
        return n;
    }
}
