package com.bolsadeploy.reports;

import com.bolsadeploy.domain.AuthType;
import com.bolsadeploy.reports.PdfDocumentWriter.Color;
import com.bolsadeploy.reports.PdfDocumentWriter.Font;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generador de documentación técnica y de accesos en formato PDF.
 *
 * <p>Produce un dossier ejecutivo ordenado, legible y listo para entregar con:
 * <ol>
 *   <li>Enlaces clicables del sitio web, inicio de sesión, panel admin y repositorio.</li>
 *   <li>Credenciales de acceso del administrador del sistema.</li>
 *   <li>Guía visual paso a paso para conectar Navicat a la base de datos (pestañas General + SSH).</li>
 *   <li>Ficha técnica del VPS, rutas del proyecto y comandos rápidos de soporte.</li>
 * </ol>
 */
public final class DocumentacionPdfGenerator {

    // Paleta moderna ejecutiva (Slate + Indigo + Esmeralda + Ámbar)
    private static final Color NAVY_DARK   = new Color(15, 23, 42);
    private static final Color NAVY_MED    = new Color(30, 41, 59);
    private static final Color INDIGO      = new Color(79, 70, 229);
    private static final Color INDIGO_SOFT = new Color(238, 242, 255);
    private static final Color INDIGO_TXT  = new Color(55, 48, 163);
    private static final Color EMERALD     = new Color(5, 150, 105);
    private static final Color EMERALD_BG  = new Color(236, 253, 245);
    private static final Color AMBER       = new Color(217, 119, 6);
    private static final Color AMBER_BG    = new Color(255, 251, 235);
    private static final Color AMBER_TXT   = new Color(146, 64, 14);
    private static final Color LINK_BLUE   = new Color(37, 99, 235);
    private static final Color CARD_BG     = new Color(248, 250, 252);
    private static final Color ROW_ALT     = new Color(241, 245, 249);
    private static final Color BORDER      = new Color(203, 213, 225);
    private static final Color BORDER_SOFT = new Color(226, 232, 240);
    private static final Color INK_DARK    = new Color(15, 23, 42);
    private static final Color INK_BODY    = new Color(51, 65, 85);
    private static final Color INK_MUTED   = new Color(100, 116, 139);
    private static final Color WHITE       = new Color(255, 255, 255);
    private static final Color CODE_BG     = new Color(15, 23, 42);
    private static final Color CODE_TEXT   = new Color(226, 232, 240);
    private static final Color CODE_ACCENT = new Color(129, 140, 248);

    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Todos los datos necesarios para generar el PDF de documentación y accesos. */
    public record DatosDocumentacion(
            String nombreProyecto,
            String slug,
            String dominio,
            String urlWeb,
            String urlLogin,
            String urlAdmin,
            boolean httpsActivo,
            String repoGit,
            String rama,
            String commitCorto,
            // Credenciales Admin Web
            String adminEmail,
            String adminPassword,
            boolean incluirAdminPassword,
            // Base de datos (Navicat General)
            String motorBd,          // MYSQL | POSTGRESQL | SQLITE | NINGUNA
            String hostBdLocal,      // 127.0.0.1
            int puertoBd,            // 3306 / 5432
            String nombreBd,
            String usuarioBd,
            String passwordBd,
            boolean incluirPasswordBd,
            // Servidor VPS (Navicat SSH Tunnel)
            String vpsNombre,
            String vpsHost,
            int vpsPuertoSsh,
            String vpsUsuarioSsh,
            AuthType vpsTipoAuth,
            String vpsSecretoSsh,
            boolean incluirSecretoSsh,
            // Rutas y notas
            String rutaBaseVps,
            String notasAdicionales,
            String fechaGeneracion
    ) {
        public String fechaEfectiva() {
            return (fechaGeneracion == null || fechaGeneracion.isBlank())
                    ? LocalDateTime.now().format(FECHA_FMT)
                    : fechaGeneracion;
        }
    }

    private record FilaTabla(String etiqueta, String valor, boolean monoespacio, String linkUri, String nota) {
        FilaTabla(String etiqueta, String valor, boolean monoespacio) {
            this(etiqueta, valor, monoespacio, null, null);
        }

        FilaTabla(String etiqueta, String valor, boolean monoespacio, String linkUri) {
            this(etiqueta, valor, monoespacio, linkUri, null);
        }
    }

    private DocumentacionPdfGenerator() {
    }

    /** Genera y guarda el PDF en {@code destino}. */
    public static void generar(DatosDocumentacion datos, Path destino) throws IOException {
        PdfDocumentWriter pdf = construirDocumento(datos);
        pdf.save(destino);
    }

    /** Genera los bytes del PDF en memoria (ideal para pruebas). */
    public static byte[] generarBytes(DatosDocumentacion datos) {
        return construirDocumento(datos).toBytes();
    }

    private static PdfDocumentWriter construirDocumento(DatosDocumentacion d) {
        PdfDocumentWriter pdf = new PdfDocumentWriter();
        String titulo = valorO(d.nombreProyecto(), "Sistema Web");
        String fecha = d.fechaEfectiva();

        pdf.setRunningHeader("Documentación de Accesos — " + titulo);
        pdf.setFooter("VPSPilot — Documentación de Entrega y Credenciales (Confidencial)", fecha);

        // ==========================================================
        // 0. PORTADA / BANNER SUPERIOR
        // ==========================================================
        dibujarCabeceraEjecutiva(pdf, d, titulo, fecha);

        // ==========================================================
        // 1. ENLACES DE ACCESO AL SISTEMA
        // ==========================================================
        dibujarSeccionEnlaces(pdf, d);

        // ==========================================================
        // 2. CREDENCIALES DE ADMINISTRADOR
        // ==========================================================
        dibujarSeccionAdmin(pdf, d);

        // ==========================================================
        // 3. FICHA TÉCNICA DEL SERVIDOR VPS Y RUTAS (completa Página 1)
        // ==========================================================
        dibujarSeccionTecnicaVps(pdf, d);

        // ==========================================================
        // 4. GUÍA PASO A PASO DE CONEXIÓN EN NAVICAT (Página dedicada limpia)
        // ==========================================================
        dibujarSeccionNavicat(pdf, d);

        // ==========================================================
        // 5. COMANDOS RÁPIDOS DE SOPORTE Y NOTAS
        // ==========================================================
        dibujarBloqueSoporteYNotas(pdf, d);

        return pdf;
    }

    // ------------------------------------------------------------------
    // Portada ejecutiva
    // ------------------------------------------------------------------

    private static void dibujarCabeceraEjecutiva(PdfDocumentWriter pdf, DatosDocumentacion d,
                                                 String titulo, String fecha) {
        double x = PdfDocumentWriter.MARGIN_X;
        double w = PdfDocumentWriter.CONTENT_WIDTH;
        double top = pdf.y();
        double bannerH = 92;

        // Fondo oscuro redondeado + franja índigo sutil centrada abajo (sin romper esquinas)
        pdf.drawRoundedRect(x, top, w, bannerH, 10, NAVY_DARK, null, 0);
        pdf.drawRoundedRect(x + 14, top + bannerH - 4, w - 28, 3, 1.5, INDIGO, null, 0);

        // Kicker superior
        pdf.drawText("DOCUMENTACIÓN DE ENTREGA, CREDENCIALES Y BASE DE DATOS",
                x + 18, top + 22, Font.BOLD, 7.8, new Color(165, 180, 252));

        // Badge de estado arriba a la derecha
        String badgeTexto = d.httpsActivo() ? "PRODUCCIÓN • HTTPS" : "PRODUCCIÓN • HTTP";
        Color badgeBg = d.httpsActivo() ? EMERALD : AMBER;
        double badgeW = pdf.measureWidth(badgeTexto, Font.BOLD, 7.5) + 16;
        pdf.drawBadge(badgeTexto, x + w - badgeW - 16, top + 12, 17, Font.BOLD, 7.5, badgeBg, WHITE);

        // Título principal (ajusta tamaño si el nombre es largo)
        double maxTitleW = w - badgeW - 44;
        double titleSize = 18.0;
        if (pdf.measureWidth(titulo, Font.BOLD, titleSize) > maxTitleW) {
            titleSize = 14.5;
        }
        List<String> titleLines = pdf.wrapText(titulo, Font.BOLD, titleSize, w - 36);
        String primeraLinea = titleLines.isEmpty() ? titulo : titleLines.get(0);
        pdf.drawText(primeraLinea, x + 18, top + 48, Font.BOLD, titleSize, WHITE);

        // Subtítulo con dominio y VPS
        String sub = "Dominio: " + valorO(d.dominio(), "-")
                + "   |   Servidor VPS: " + valorO(d.vpsNombre(), "VPS")
                + " (" + valorO(d.vpsHost(), "-") + ")";
        pdf.drawText(truncar(pdf, sub, Font.REGULAR, 9.2, w - 36),
                x + 18, top + 68, Font.REGULAR, 9.2, new Color(203, 213, 225));

        // Barra de metadatos justo debajo del banner
        double stripTop = top + bannerH + 6;
        double stripH = 24;
        pdf.drawRoundedRect(x, stripTop, w, stripH, 6, CARD_BG, BORDER_SOFT, 0.7);

        String motorLabel = motorLegible(d.motorBd());
        String version = (d.rama() == null || d.rama().isBlank() ? "main" : d.rama())
                + (d.commitCorto() == null || d.commitCorto().isBlank() ? "" : " (" + d.commitCorto() + ")");
        String meta = "Generado: " + fecha
                + "    |    Motor BD: " + motorLabel
                + "    |    Versión: " + version;
        pdf.drawText(truncar(pdf, meta, Font.REGULAR, 8.3, w - 24),
                x + 12, stripTop + 15, Font.REGULAR, 8.3, INK_BODY);

        pdf.setY(stripTop + stripH + 16);
    }

    // ------------------------------------------------------------------
    // Sección 1: Enlaces
    // ------------------------------------------------------------------

    private static void dibujarSeccionEnlaces(PdfDocumentWriter pdf, DatosDocumentacion d) {
        List<FilaTabla> filas = new ArrayList<>();
        String web = normalizarUrl(d.urlWeb(), d.dominio(), d.httpsActivo(), "/");
        String login = normalizarUrl(d.urlLogin(), d.dominio(), d.httpsActivo(), "/login");
        String admin = normalizarUrl(d.urlAdmin(), d.dominio(), d.httpsActivo(), "/admin/dashboard");

        filas.add(new FilaTabla("Sitio Web Principal", web, true, web));
        filas.add(new FilaTabla("Inicio de Sesión (Login)", login, true, login));
        if (!admin.isBlank() && !admin.equalsIgnoreCase(login)) {
            filas.add(new FilaTabla("Panel de Administración", admin, true, admin));
        }
        String repoUrl = resolverUrlRepo(d.repoGit());
        if (!repoUrl.isBlank()) {
            filas.add(new FilaTabla("Repositorio de Código", repoUrl, true, repoUrl));
        }

        double altoEstimado = 26 + calcularAltoTabla(pdf, filas, 145);
        pdf.ensureSpace(altoEstimado + 12);

        dibujarTituloSeccion(pdf, "1", "Enlaces de Acceso al Sistema",
                "Haz clic directamente en cualquier enlace para abrirlo en tu navegador.", EMERALD);
        dibujarTablaClaveValor(pdf, filas, 145, EMERALD);
        pdf.advanceY(14);
    }

    // ------------------------------------------------------------------
    // Sección 2: Credenciales Admin
    // ------------------------------------------------------------------

    private static void dibujarSeccionAdmin(PdfDocumentWriter pdf, DatosDocumentacion d) {
        String login = normalizarUrl(d.urlLogin(), d.dominio(), d.httpsActivo(), "/login");
        String email = (d.adminEmail() == null || d.adminEmail().isBlank())
                ? "(No registrado — completar al primer acceso)"
                : d.adminEmail().trim();
        String pass;
        if (!d.incluirAdminPassword()) {
            pass = "(Oculta en este PDF por preferencia de seguridad)";
        } else if (d.adminPassword() == null || d.adminPassword().isBlank()) {
            pass = "(No registrada en VPSPilot)";
        } else {
            pass = d.adminPassword();
        }

        List<FilaTabla> filas = List.of(
                new FilaTabla("Enlace de Acceso", login, true, login),
                new FilaTabla("Usuario / Correo Admin", email, true),
                new FilaTabla("Contraseña Admin", pass, true)
        );

        double altoTabla = calcularAltoTabla(pdf, filas, 145);
        double altoTip = 28;
        pdf.ensureSpace(26 + altoTabla + altoTip + 18);

        dibujarTituloSeccion(pdf, "2", "Credenciales de Administrador",
                "Datos para ingresar al panel de gestión del sistema web.", INDIGO);
        dibujarTablaClaveValor(pdf, filas, 145, INDIGO);
        pdf.advanceY(6);

        dibujarCajaAviso(pdf, "Recomendación de seguridad:",
                "Guarda este PDF en un lugar privado y cambia la contraseña inicial si el acceso fue compartido.",
                AMBER_BG, AMBER, AMBER_TXT);
        pdf.advanceY(16);
    }

    // ------------------------------------------------------------------
    // Sección 3: Guía Navicat Paso a Paso
    // ------------------------------------------------------------------

    private static void dibujarSeccionNavicat(PdfDocumentWriter pdf, DatosDocumentacion d) {
        String motor = d.motorBd() == null ? "MYSQL" : d.motorBd().trim().toUpperCase();

        // Caso sin BD
        if ("NINGUNA".equals(motor)) {
            pdf.ensureSpace(80);
            dibujarTituloSeccion(pdf, "4", "Configuración de Base de Datos (Navicat)",
                    "Estado del motor de base de datos para este proyecto.", INDIGO);
            dibujarCajaAviso(pdf, "Sin base de datos:",
                    "Esta instalación está configurada sin motor de base de datos (modo NINGUNA), por lo que no requiere conexión en Navicat.",
                    INDIGO_SOFT, INDIGO, INDIGO_TXT);
            pdf.advanceY(14);
            return;
        }

        // Caso SQLite
        if ("SQLITE".equals(motor)) {
            String rutaSqlite = rutaBaseEfectiva(d) + "/shared/database.sqlite";
            pdf.ensureSpace(175);
            dibujarTituloSeccion(pdf, "4", "Acceso a Base de Datos SQLite en Navicat",
                    "Este proyecto usa SQLite almacenado directamente en el VPS.", INDIGO);
            List<FilaTabla> filas = List.of(
                    new FilaTabla("Motor", "SQLite 3", false),
                    new FilaTabla("Archivo en VPS", rutaSqlite, true),
                    new FilaTabla("Servidor SFTP", valorO(d.vpsHost(), "-") + " (puerto " + puertoSshEfectivo(d) + ")", true),
                    new FilaTabla("Usuario SFTP / SSH", valorO(d.vpsUsuarioSsh(), "root"), true)
            );
            dibujarTablaClaveValor(pdf, filas, 145, INDIGO);
            pdf.advanceY(8);
            dibujarCajaAviso(pdf, "Cómo abrir en Navicat:",
                    "1) Descarga '" + rutaSqlite + "' usando WinSCP/SFTP.  2) En Navicat ve a Archivo -> Nueva conexión -> SQLite -> Tipo: Archivo SQLite existente y selecciona el archivo descargado.",
                    INDIGO_SOFT, INDIGO, INDIGO_TXT);
            pdf.advanceY(14);
            return;
        }

        // Caso MySQL / MariaDB o PostgreSQL (guía completa General + Túnel SSH)
        boolean esPg = "POSTGRESQL".equals(motor);
        String nombreNavicatMotor = esPg ? "PostgreSQL" : "MySQL";
        int puertoBd = d.puertoBd() > 0 ? d.puertoBd() : (esPg ? 5432 : 3306);
        String hostLocal = (d.hostBdLocal() == null || d.hostBdLocal().isBlank()) ? "127.0.0.1" : d.hostBdLocal().trim();
        String nombreConexion = "Produccion - " + valorO(d.nombreProyecto(), valorO(d.slug(), "VPS"));

        String passBdTexto;
        if (!d.incluirPasswordBd()) {
            passBdTexto = "(Oculta en este PDF por seguridad — ver shared/.env)";
        } else if (d.passwordBd() == null || d.passwordBd().isBlank()) {
            passBdTexto = "(No guardada — consultar DB_PASSWORD en shared/.env)";
        } else {
            passBdTexto = d.passwordBd();
        }

        boolean esLlave = d.vpsTipoAuth() == AuthType.KEY;
        String authSshTexto = esLlave ? "Clave pública (llave privada SSH)" : "Contraseña";
        String secretoSshTexto;
        if (!d.incluirSecretoSsh()) {
            secretoSshTexto = "(Oculta en este PDF — usar tu credencial de acceso al VPS)";
        } else if (esLlave) {
            if (d.vpsSecretoSsh() != null && !d.vpsSecretoSsh().isBlank() && !d.vpsSecretoSsh().contains("BEGIN ")) {
                secretoSshTexto = d.vpsSecretoSsh().trim();
            } else {
                secretoSshTexto = "Seleccionar tu archivo de clave privada (.pem / id_ed25519 / id_rsa)";
            }
        } else {
            secretoSshTexto = (d.vpsSecretoSsh() == null || d.vpsSecretoSsh().isBlank())
                    ? "(No guardada en VPSPilot)"
                    : d.vpsSecretoSsh();
        }

        // Iniciar la guía completa de Navicat en una página limpia para que todos los pasos (1 al 4) queden juntos
        if (pdf.remainingHeight() < 620) {
            pdf.newPage(true);
        }
        dibujarTituloSeccion(pdf, "4", "Configuración en Navicat para Ingresar a la Base de Datos",
                "Guía paso a paso lista para copiar y pegar (conexión segura mediante Túnel SSH).", INDIGO);

        // Caja explicativa por qué se usan 2 pestañas
        dibujarCajaAviso(pdf, "Importante antes de empezar",
                "Por seguridad, " + nombreNavicatMotor + " en el VPS solo escucha de forma local (127.0.0.1). "
                        + "En Navicat debes llenar DOS pestañas: primero 'General' (con Host 127.0.0.1) y luego 'SSH' (con la IP del VPS).",
                INDIGO_SOFT, INDIGO, INDIGO_TXT);
        pdf.advanceY(8);

        // PASO 1
        dibujarPasoNumerado(pdf, "1", "Crear una nueva conexión en Navicat",
                "Abre Navicat y haz clic en:  Archivo  ->  Nueva conexión  ->  " + nombreNavicatMotor + ".");
        pdf.advanceY(6);

        // PASO 2 — Pestaña General
        List<FilaTabla> filasGeneral = new ArrayList<>();
        filasGeneral.add(new FilaTabla("Nombre de conexión", nombreConexion, false));
        filasGeneral.add(new FilaTabla("Host / Dirección IP", hostLocal, true, null,
                "DEBE ser 127.0.0.1 (no la IP pública del VPS)"));
        filasGeneral.add(new FilaTabla("Puerto", String.valueOf(puertoBd), true));
        if (esPg) {
            filasGeneral.add(new FilaTabla("Base de datos inicial", valorO(d.nombreBd(), "postgres"), true));
        }
        filasGeneral.add(new FilaTabla("Nombre de usuario", valorO(d.usuarioBd(), "(ver DB_USERNAME en .env)"), true));
        filasGeneral.add(new FilaTabla("Contraseña", passBdTexto, true));
        if (!esPg) {
            filasGeneral.add(new FilaTabla("Base de datos a abrir", valorO(d.nombreBd(), "(ver DB_DATABASE en .env)"), true, null,
                    "Aparecerá al conectarte en el panel izquierdo"));
        }

        double altoPaso2 = 24 + 22 + calcularAltoTabla(pdf, filasGeneral, 150) + 10;
        pdf.ensureSpace(altoPaso2);
        dibujarPasoNumerado(pdf, "2", "Llenar la pestaña \"General\" en Navicat",
                "Completa los campos de la pestaña General exactamente con estos datos:");
        dibujarBarraPestanasNavicat(pdf, true);
        dibujarTablaClaveValor(pdf, filasGeneral, 150, INDIGO);
        pdf.advanceY(8);

        // PASO 3 — Pestaña SSH
        List<FilaTabla> filasSsh = new ArrayList<>();
        filasSsh.add(new FilaTabla("Usar túnel SSH", "[X] ACTIVADO  (Marcar esta casilla obligatoriamente)", false));
        filasSsh.add(new FilaTabla("Nombre de host / IP", valorO(d.vpsHost(), "-"), true));
        filasSsh.add(new FilaTabla("Puerto", String.valueOf(puertoSshEfectivo(d)), true));
        filasSsh.add(new FilaTabla("Nombre de usuario", valorO(d.vpsUsuarioSsh(), "root"), true));
        filasSsh.add(new FilaTabla("Método de autenticación", authSshTexto, false));
        filasSsh.add(new FilaTabla(esLlave ? "Clave privada" : "Contraseña SSH", secretoSshTexto, true));

        double altoPaso3 = 24 + 22 + calcularAltoTabla(pdf, filasSsh, 150) + 10;
        pdf.ensureSpace(altoPaso3);
        dibujarPasoNumerado(pdf, "3", "Llenar la pestaña \"SSH\" en Navicat (Túnel al VPS)",
                "Cambia a la pestaña SSH, activa 'Usar túnel SSH' y llena los datos del servidor VPS:");
        dibujarBarraPestanasNavicat(pdf, false);
        dibujarTablaClaveValor(pdf, filasSsh, 150, EMERALD);
        pdf.advanceY(8);

        // PASO 4 + Tips rápidos
        pdf.ensureSpace(95);
        dibujarPasoNumerado(pdf, "4", "Probar la conexión y guardar",
                "Haz clic en 'Probar conexión' (abajo a la izquierda). Si aparece 'Conexión exitosa', pulsa 'Aceptar' y haz doble clic sobre '"
                        + nombreConexion + "' para abrir la base de datos '" + valorO(d.nombreBd(), "-") + "'.");
        pdf.advanceY(6);

        dibujarCajaAviso(pdf, "Solución rápida si Navicat muestra un error",
                "• 'Access denied': revisa Usuario/Contraseña de BD en la pestaña General (confirmar con shared/.env).  "
                        + "• 'Can't connect / Timed out': verifica que 'Usar túnel SSH' esté marcado en la pestaña SSH y que en General el Host sea 127.0.0.1.",
                AMBER_BG, AMBER, AMBER_TXT);
        pdf.advanceY(12);
    }

    // ------------------------------------------------------------------
    // Sección 3: Ficha Técnica VPS y Rutas
    // ------------------------------------------------------------------

    private static void dibujarSeccionTecnicaVps(PdfDocumentWriter pdf, DatosDocumentacion d) {
        String rutaBase = rutaBaseEfectiva(d);
        String slug = valorO(d.slug(), "app");
        String ip = valorO(d.vpsHost(), "-");
        int puerto = puertoSshEfectivo(d);

        List<FilaTabla> filas = List.of(
                new FilaTabla("Servidor VPS", valorO(d.vpsNombre(), "VPS") + " (" + ip + ":" + puerto + ")", false),
                new FilaTabla("Ruta Base del Proyecto", rutaBase, true),
                new FilaTabla("Release Activa (current)", rutaBase + "/current", true),
                new FilaTabla("Archivo de Entorno (.env)", rutaBase + "/shared/.env", true),
                new FilaTabla("Logs de Aplicación", rutaBase + "/shared/storage/logs/laravel.log", true),
                new FilaTabla("Respaldos en VPS", rutaBase + "/backups", true),
                new FilaTabla("Vhost de Nginx", "/etc/nginx/sites-available/bolsa-" + slug + ".conf", true)
        );

        double altoTabla = calcularAltoTabla(pdf, filas, 150);
        pdf.ensureSpace(36 + altoTabla + 10);

        dibujarTituloSeccion(pdf, "3", "Información Técnica del Servidor VPS y Rutas",
                "Referencia rápida de directorios, logs y configuración en producción.", NAVY_MED);
        dibujarTablaClaveValor(pdf, filas, 150, NAVY_MED);
        pdf.advanceY(12);
    }

    private static void dibujarBloqueSoporteYNotas(PdfDocumentWriter pdf, DatosDocumentacion d) {
        String rutaBase = rutaBaseEfectiva(d);
        String ip = valorO(d.vpsHost(), "-");
        int puerto = puertoSshEfectivo(d);
        String user = valorO(d.vpsUsuarioSsh(), "root");

        List<String> comandos = List.of(
                "# Conectarse por terminal SSH al VPS",
                "ssh " + user + "@" + ip + (puerto != 22 ? " -p " + puerto : ""),
                "# Ver últimos errores de la aplicación en tiempo real",
                "tail -n 50 " + rutaBase + "/shared/storage/logs/laravel.log",
                "# Recargar Nginx y PHP-FPM sin caídas",
                "sudo systemctl reload nginx && sudo systemctl reload php*-fpm"
        );
        pdf.ensureSpace(150);
        dibujarTituloSeccion(pdf, "5", "Comandos Rápidos de Soporte (SSH)",
                "Comandos útiles de diagnóstico y mantenimiento directo en el VPS.", NAVY_MED);
        dibujarBloqueComandos(pdf, "Terminal SSH — Referencia Rápida", comandos);
        pdf.advanceY(14);

        if (d.notasAdicionales() != null && !d.notasAdicionales().isBlank()) {
            dibujarSeccionNotas(pdf, d.notasAdicionales().trim());
        }
    }

    private static void dibujarSeccionNotas(PdfDocumentWriter pdf, String notas) {
        List<String> lines = pdf.wrapText(notas, Font.REGULAR, 9.0, PdfDocumentWriter.CONTENT_WIDTH - 28);
        double h = 16 + (lines.size() * 13.0) + 12;
        pdf.ensureSpace(h + 36);

        dibujarTituloSeccion(pdf, "6", "Notas Adicionales", "", INK_MUTED);
        double x = PdfDocumentWriter.MARGIN_X;
        double top = pdf.y();
        pdf.drawRoundedRect(x, top, PdfDocumentWriter.CONTENT_WIDTH, h, 6, CARD_BG, BORDER, 0.7);
        double ty = top + 18;
        for (String line : lines) {
            pdf.drawText(line, x + 14, ty, Font.REGULAR, 9.0, INK_BODY);
            ty += 13.0;
        }
        pdf.setY(top + h + 12);
    }

    // ------------------------------------------------------------------
    // Componentes visuales reutilizables
    // ------------------------------------------------------------------

    private static void dibujarTituloSeccion(PdfDocumentWriter pdf, String numero, String titulo,
                                             String subtitulo, Color acento) {
        double x = PdfDocumentWriter.MARGIN_X;
        double top = pdf.y();

        // Círculo numerado de sección
        pdf.fillCircle(x + 9, top + 9, 9.0, acento);
        pdf.drawCenteredText(numero, x + 9, top + 12.2, Font.BOLD, 9.5, WHITE);

        pdf.drawText(titulo, x + 25, top + 12.5, Font.BOLD, 12.0, INK_DARK);
        if (subtitulo != null && !subtitulo.isBlank()) {
            pdf.drawText(subtitulo, x + 25, top + 24.5, Font.REGULAR, 8.3, INK_MUTED);
            pdf.drawLine(x, top + 30, x + PdfDocumentWriter.CONTENT_WIDTH, top + 30, 0.8, BORDER_SOFT);
            pdf.setY(top + 37);
        } else {
            pdf.drawLine(x, top + 21, x + PdfDocumentWriter.CONTENT_WIDTH, top + 21, 0.8, BORDER_SOFT);
            pdf.setY(top + 28);
        }
    }

    private static void dibujarPasoNumerado(PdfDocumentWriter pdf, String numero, String titulo, String desc) {
        double x = PdfDocumentWriter.MARGIN_X;
        double w = PdfDocumentWriter.CONTENT_WIDTH;
        List<String> descLines = pdf.wrapText(desc, Font.REGULAR, 8.8, w - 28);
        double needed = 16 + (descLines.size() * 12.2) + 4;
        pdf.ensureSpace(needed);

        double top = pdf.y();
        pdf.fillCircle(x + 8, top + 7.5, 7.5, INDIGO_SOFT);
        pdf.drawCenteredText(numero, x + 8, top + 10.5, Font.BOLD, 8.5, INDIGO_TXT);

        pdf.drawText(titulo, x + 22, top + 10.5, Font.BOLD, 9.8, INK_DARK);
        double ty = top + 23.0;
        for (String line : descLines) {
            pdf.drawText(line, x + 22, ty, Font.REGULAR, 8.8, INK_BODY);
            ty += 12.2;
        }
        pdf.setY(ty - 4);
    }

    /** Dibuja una barra visual simulando las pestañas reales de Navicat (General | SSH | SSL | Avanzado). */
    private static void dibujarBarraPestanasNavicat(PdfDocumentWriter pdf, boolean generalActiva) {
        double x = PdfDocumentWriter.MARGIN_X;
        double w = PdfDocumentWriter.CONTENT_WIDTH;
        double top = pdf.y() + 2;
        double tabH = 20;

        pdf.drawRoundedRect(x, top, w, tabH, 5, ROW_ALT, BORDER_SOFT, 0.6);

        String[] tabs = {"General", "SSH", "SSL", "Avanzado"};
        double tx = x + 6;
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == 0 && generalActiva) || (i == 1 && !generalActiva);
            String t = tabs[i];
            double tw = pdf.measureWidth(t, Font.BOLD, 8.2) + 20;
            if (active) {
                Color activeBg = generalActiva ? INDIGO : EMERALD;
                pdf.drawRoundedRect(tx, top + 2.5, tw, tabH - 5, 4, activeBg, null, 0);
                pdf.drawCenteredText(t, tx + tw / 2.0, top + 13.0, Font.BOLD, 8.2, WHITE);
            } else {
                pdf.drawCenteredText(t, tx + tw / 2.0, top + 13.0, Font.REGULAR, 8.0, INK_MUTED);
            }
            tx += tw + 4;
        }
        String hint = generalActiva ? "Pestaña activa: General" : "Pestaña activa: SSH (Túnel)";
        pdf.drawText(hint, x + w - pdf.measureWidth(hint, Font.ITALIC, 7.8) - 10,
                top + 13.0, Font.ITALIC, 7.8, INK_MUTED);

        pdf.setY(top + tabH + 3);
    }

    private static double calcularAltoTabla(PdfDocumentWriter pdf, List<FilaTabla> filas, double labelColW) {
        double valMaxW = PdfDocumentWriter.CONTENT_WIDTH - labelColW - 28;
        double total = 4;
        for (FilaTabla f : filas) {
            Font valFont = f.monoespacio() ? Font.MONO_BOLD : Font.BOLD;
            double valSize = f.monoespacio() ? 8.6 : 9.0;
            List<String> lines = pdf.wrapText(valorO(f.valor(), "-"), valFont, valSize, valMaxW);
            int nLines = Math.max(1, lines.size());
            double rowH = Math.max(22, (nLines * 12.2) + 10);
            if (f.nota() != null && !f.nota().isBlank()) {
                rowH += 11.0;
            }
            total += rowH;
        }
        return total;
    }

    private static void dibujarTablaClaveValor(PdfDocumentWriter pdf, List<FilaTabla> filas,
                                               double labelColW, Color acentoIzquierdo) {
        double x = PdfDocumentWriter.MARGIN_X;
        double w = PdfDocumentWriter.CONTENT_WIDTH;
        double valX = x + labelColW + 14;
        double valMaxW = w - labelColW - 28;
        double totalH = calcularAltoTabla(pdf, filas, labelColW);
        double top = pdf.y();

        // Contenedor redondeado
        pdf.drawRoundedRect(x, top, w, totalH, 7, WHITE, BORDER, 0.75);
        // Franja de color en borde izquierdo
        pdf.fillRect(x, top + 4, 3.5, totalH - 8, acentoIzquierdo);

        double rowY = top + 2;
        for (int i = 0; i < filas.size(); i++) {
            FilaTabla f = filas.get(i);
            Font valFont = f.monoespacio() ? Font.MONO_BOLD : Font.BOLD;
            double valSize = f.monoespacio() ? 8.6 : 9.0;
            List<String> lines = pdf.wrapText(valorO(f.valor(), "-"), valFont, valSize, valMaxW);
            int nLines = Math.max(1, lines.size());
            double rowH = Math.max(22, (nLines * 12.2) + 10);
            if (f.nota() != null && !f.nota().isBlank()) {
                rowH += 11.0;
            }

            if (i % 2 == 1) {
                pdf.fillRect(x + 4, rowY, w - 5, rowH, CARD_BG);
            }
            if (i > 0) {
                pdf.drawLine(x + 4, rowY, x + w - 1, rowY, 0.5, BORDER_SOFT);
            }

            // Etiqueta izquierda
            pdf.drawText(f.etiqueta(), x + 12, rowY + 14.5, Font.BOLD, 8.6, INK_BODY);

            // Valor derecho (enlace clicable o texto normal/monoespaciado)
            double vy = rowY + 14.5;
            if (f.linkUri() != null && !f.linkUri().isBlank()) {
                for (String line : lines) {
                    pdf.drawLinkText(line, f.linkUri(), valX, vy, Font.MONO_BOLD, 8.5, LINK_BLUE);
                    vy += 12.2;
                }
            } else {
                Color valColor = f.valor() != null && f.valor().startsWith("(") ? INK_MUTED : INK_DARK;
                for (String line : lines) {
                    pdf.drawText(line, valX, vy, valFont, valSize, valColor);
                    vy += 12.2;
                }
            }

            if (f.nota() != null && !f.nota().isBlank()) {
                pdf.drawText(f.nota(), valX, vy - 1.5, Font.ITALIC, 7.7, INK_MUTED);
            }

            rowY += rowH;
        }

        pdf.setY(top + totalH);
    }

    private static void dibujarCajaAviso(PdfDocumentWriter pdf, String titulo, String cuerpo,
                                         Color bg, Color bordeIzq, Color colorTexto) {
        double x = PdfDocumentWriter.MARGIN_X;
        double w = PdfDocumentWriter.CONTENT_WIDTH;
        List<String> lines = pdf.wrapText(cuerpo, Font.REGULAR, 8.3, w - 24);
        double h = 18 + (lines.size() * 11.4) + 8;
        pdf.ensureSpace(h + 6);

        double top = pdf.y();
        pdf.drawRoundedRect(x, top, w, h, 6, bg, bordeIzq, 0.5);
        pdf.fillRect(x, top + 3, 3.5, h - 6, bordeIzq);

        pdf.drawText(titulo, x + 12, top + 13.5, Font.BOLD, 8.4, colorTexto);
        double ty = top + 25.0;
        for (String line : lines) {
            pdf.drawText(line, x + 12, ty, Font.REGULAR, 8.3, colorTexto);
            ty += 11.4;
        }
        pdf.setY(top + h);
    }

    private static void dibujarBloqueComandos(PdfDocumentWriter pdf, String titulo, List<String> lineas) {
        double x = PdfDocumentWriter.MARGIN_X;
        double w = PdfDocumentWriter.CONTENT_WIDTH;
        List<String> rendered = new ArrayList<>();
        for (String l : lineas) {
            rendered.addAll(pdf.wrapText(l, Font.MONO, 8.1, w - 28));
        }
        double h = 24 + (rendered.size() * 11.5) + 10;
        pdf.ensureSpace(h + 8);

        double top = pdf.y();
        pdf.drawRoundedRect(x, top, w, h, 7, CODE_BG, null, 0);
        pdf.drawText(titulo.toUpperCase(), x + 14, top + 15.5, Font.BOLD, 7.6, CODE_ACCENT);

        double ty = top + 29.0;
        for (String l : rendered) {
            boolean esComentario = l.trim().startsWith("#");
            pdf.drawText(l, x + 14, ty, esComentario ? Font.MONO : Font.MONO_BOLD,
                    8.1, esComentario ? new Color(148, 163, 184) : CODE_TEXT);
            ty += 11.5;
        }
        pdf.setY(top + h);
    }

    // ------------------------------------------------------------------
    // Utilidades de formato
    // ------------------------------------------------------------------

    private static String motorLegible(String motor) {
        if (motor == null || motor.isBlank()) return "MySQL / MariaDB";
        return switch (motor.trim().toUpperCase()) {
            case "POSTGRESQL" -> "PostgreSQL";
            case "SQLITE" -> "SQLite";
            case "NINGUNA" -> "Sin base de datos";
            default -> "MySQL / MariaDB";
        };
    }

    private static int puertoSshEfectivo(DatosDocumentacion d) {
        return d.vpsPuertoSsh() > 0 ? d.vpsPuertoSsh() : 22;
    }

    private static String rutaBaseEfectiva(DatosDocumentacion d) {
        if (d.rutaBaseVps() != null && !d.rutaBaseVps().isBlank()) {
            return d.rutaBaseVps().trim().replaceAll("/+$", "");
        }
        return "/var/www/bolsadeploy/" + valorO(d.slug(), "app");
    }

    private static String normalizarUrl(String explicita, String dominio, boolean https, String pathDefault) {
        if (explicita != null && !explicita.isBlank()) {
            String u = explicita.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = (https ? "https://" : "http://") + u;
            }
            return u;
        }
        if (dominio == null || dominio.isBlank()) {
            return "";
        }
        String base = (https ? "https://" : "http://") + dominio.trim().replaceAll("/+$", "");
        return "/".equals(pathDefault) ? base : base + pathDefault;
    }

    private static String resolverUrlRepo(String repo) {
        if (repo == null || repo.isBlank()) {
            return "";
        }
        String r = repo.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return r.replaceAll("\\.git$", "");
        }
        if (r.contains("/") && !r.contains(" ")) {
            return "https://github.com/" + r.replaceAll("\\.git$", "");
        }
        return r;
    }

    private static String valorO(String s, String defecto) {
        return (s == null || s.isBlank()) ? defecto : s.trim();
    }

    private static String truncar(PdfDocumentWriter pdf, String texto, Font font, double size, double maxWidth) {
        if (pdf.measureWidth(texto, font, size) <= maxWidth) {
            return texto;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texto.length(); i++) {
            String cand = sb.toString() + texto.charAt(i) + "...";
            if (pdf.measureWidth(cand, font, size) > maxWidth) {
                break;
            }
            sb.append(texto.charAt(i));
        }
        return sb + "...";
    }
}
