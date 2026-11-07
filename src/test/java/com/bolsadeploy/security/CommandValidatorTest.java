package com.bolsadeploy.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas del validador/escape de comandos remotos (§14 seguridad). */
class CommandValidatorTest {

    // ------------------------------------------------------------ Slugs
    @Test
    void slugValido() {
        assertTrue(CommandValidator.esSlug("iestp-demo"));
        assertTrue(CommandValidator.esSlug("u-norte-2"));
    }

    @Test
    void slugInvalido() {
        assertFalse(CommandValidator.esSlug(null));
        assertFalse(CommandValidator.esSlug(""));
        assertFalse(CommandValidator.esSlug("-inicio"));
        assertFalse(CommandValidator.esSlug("con espacios"));
        assertFalse(CommandValidator.esSlug("rm -rf /"));
        assertFalse(CommandValidator.esSlug("a")); // demasiado corto
    }

    @Test
    void aSlugDeNombreReal() {
        assertEquals("institucion-tecnica-norte", CommandValidator.aSlug("Institución Técnica Norte"));
        assertTrue(CommandValidator.esSlug(CommandValidator.aSlug("I.E.S.T.P. \"San Martín\"")));
    }

    // ---------------------------------------------------------- Dominios
    @Test
    void dominios() {
        assertTrue(CommandValidator.esDominio("bolsa-trabajo.gob.pe"));
        assertTrue(CommandValidator.esDominio("www.ejemplo.edu.co"));
        assertFalse(CommandValidator.esDominio("localhost"));
        assertFalse(CommandValidator.esDominio("http://ejemplo.com"));
        assertFalse(CommandValidator.esDominio("ejemplo.com/ruta"));
        assertFalse(CommandValidator.esDominio("bad domain.com"));
        assertThrows(IllegalArgumentException.class, () -> CommandValidator.exigirDominio("no es dominio"));
    }

    // ------------------------------------------------------- Comillas/SQL
    @Test
    void escaparShellNeutralizaComillas() {
        assertEquals("'hola'", CommandValidator.escaparShell("hola"));
        assertEquals("'a'\\''b'", CommandValidator.escaparShell("a'b"));
        assertEquals("''", CommandValidator.escaparShell(null));
        // Un valor hostil queda como un ÚNICO argumento literal de shell:
        // cada comilla internas se cierra y reabre con '\'' (forma POSIX).
        assertEquals("''\\''; rm -rf /; '\\'''", CommandValidator.escaparShell("'; rm -rf /; '"));
    }

    @Test
    void escaparSqlDuplicaComillas() {
        assertEquals("O''Connor", CommandValidator.escaparSql("O'Connor"));
        assertEquals("sin comillas", CommandValidator.escaparSql("sin comillas"));
    }

    // ------------------------------------------------------------ Git/SHA
    @Test
    void referenciasGit() {
        assertTrue(CommandValidator.esGitRef("main"));
        assertTrue(CommandValidator.esGitRef("release/1.2"));
        assertTrue(CommandValidator.esGitRef("v2.0.1"));
        assertFalse(CommandValidator.esGitRef("main; curl evil"));
        assertFalse(CommandValidator.esGitRef("$(whoami)"));
        assertThrows(IllegalArgumentException.class, () -> CommandValidator.exigirGitRef("rama mala"));
    }

    @Test
    void shaDeCommit() {
        assertTrue(CommandValidator.esSha("ad268b9"));
        assertTrue(CommandValidator.esSha("ad268b9c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a"));
        assertFalse(CommandValidator.esSha("zzzzzzz"));
        assertFalse(CommandValidator.esSha("abc; rm"));
    }

    // ------------------------------------------------------------- Otros
    @Test
    void nombresSqlYEmail() {
        assertTrue(CommandValidator.esNombreSql("bolsa_ies_demo"));
        assertFalse(CommandValidator.esNombreSql("bolsa-demo"));
        assertFalse(CommandValidator.esNombreSql("1empieza"));
        assertTrue(CommandValidator.esEmail("admin@ejemplo.gob.pe"));
        assertFalse(CommandValidator.esEmail("admin@"));
    }

    @Test
    void rutaAbsoluta() {
        assertTrue(CommandValidator.esRutaAbsoluta("/var/www/bolsadeploy/ies-demo"));
        assertFalse(CommandValidator.esRutaAbsoluta("relativa/alguna"));
        assertFalse(CommandValidator.esRutaAbsoluta("/ruta\ncon salto"));
    }
}
