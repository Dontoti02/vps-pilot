package com.bolsadeploy.deployment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas del bloque server de Nginx (puro, sin E/S). */
class NginxConfigBuilderTest {

    private static final String[] DOMINIOS = {"bolsa.ejemplo.gob.pe", "www.bolsa.ejemplo.gob.pe"};

    @Test
    void configBasicaCorrecta() {
        String conf = NginxConfigBuilder.servidor(DOMINIOS,
                "/var/www/bolsadeploy/ies-demo/current/public",
                "unix:/run/php/php8.3-fpm.sock", "ies-demo", 16);

        assertTrue(conf.contains("server_name bolsa.ejemplo.gob.pe www.bolsa.ejemplo.gob.pe;"));
        assertTrue(conf.contains("root /var/www/bolsadeploy/ies-demo/current/public;"));
        assertTrue(conf.contains("client_max_body_size 16M;"));
        assertTrue(conf.contains("fastcgi_pass unix:/run/php/php8.3-fpm.sock;"));
    }

    @Test
    void scriptFilenameVaAntesDelInclude() {
        String conf = NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/current/public",
                "unix:/run/php/php8.3-fpm.sock", "ies-demo", 12);
        int script = conf.indexOf("fastcgi_param SCRIPT_FILENAME");
        int include = conf.indexOf("include fastcgi_params;");
        assertTrue(script > 0 && include > script,
                "SCRIPT_FILENAME debe declararse ANTES del include para ganar la precedencia");
    }

    @Test
    void protegeArchivosOcultosYPhpEnUploads() {
        String conf = NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/current/public",
                "127.0.0.1:9000", "ies-demo", 12);
        assertTrue(conf.contains("location ~ /\\.(?!well-known)"));
        assertTrue(conf.contains("deny all;"));
        assertTrue(conf.contains("location ~* /(?:uploads|storage)/.*\\.php$"));
    }

    @Test
    void limitaSubidaMinimaParaCvs() {
        String conf = NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/current/public",
                "unix:/run/php/php8.3-fpm.sock", "ies-demo", 2);
        assertTrue(conf.contains("client_max_body_size 8M;"), "mínimo 8M aunque se pida menos");
    }

    @Test
    void rechazaEntradasInvalidas() {
        assertThrows(IllegalArgumentException.class,
                () -> NginxConfigBuilder.servidor(null, "/var/www/x/public",
                        "unix:/run/php/php8.3-fpm.sock", "ies-demo", 12));
        assertThrows(IllegalArgumentException.class,
                () -> NginxConfigBuilder.servidor(DOMINIOS, "ruta/relativa",
                        "unix:/run/php/php8.3-fpm.sock", "ies-demo", 12));
        assertThrows(IllegalArgumentException.class,
                () -> NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/public",
                        "tcp://algo-raro", "ies-demo", 12));
        assertThrows(IllegalArgumentException.class,
                () -> NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/public",
                        "unix:/run/php/php8.3-fpm.sock", "slug malo", 12));
        assertThrows(IllegalArgumentException.class,
                () -> NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/public",
                        "unix:/run/php/php8.3-fpm.sock", "ies-demo", 5000));
    }

    @Test
    void archivoPerteneceSoloALaInstitucion() {
        String conf = NginxConfigBuilder.servidor(DOMINIOS, "/var/www/x/current/public",
                "unix:/run/php/php8.3-fpm.sock", "ies-demo", 12);
        assertTrue(conf.contains("institución: ies-demo"));
        assertTrue(NginxConfigBuilder.cabecera("otra").contains("otra"));
    }
}
