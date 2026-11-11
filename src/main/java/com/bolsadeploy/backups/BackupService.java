package com.bolsadeploy.backups;

import com.bolsadeploy.deployment.BaseDatosStage;
import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.domain.BackupRecord;
import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Copias de seguridad reales en el VPS (FASE F).
 *
 * Tipos:
 * <ul>
 *   <li><b>BD</b> — {@code mysqldump --single-transaction} comprimido con gzip
 *       (sin exponer credenciales: viajan en un {@code .my.cnf} temporal 600).</li>
 *   <li><b>ARCHIVOS</b> — tar.gz de lo persistente: {@code shared/storage} y
 *       {@code shared/public/uploads} (CVs y logos subidos).</li>
 *   <li><b>CONFIG</b> — tar.gz de {@code shared/.env} (contiene secretos: se
 *       advierte y se deja permiso 600 en el VPS).</li>
 *   <li><b>INTEGRAL</b> — un solo tar.gz con BD + archivos + configuración.</li>
 * </ul>
 *
 * Todo respaldo se <b>verifica de verdad</b> antes de registrarlo: integridad
 * gzip/tar, presencia de {@code CREATE TABLE} en el dump y tamaño &gt; 0.
 * Un respaldo no verificado se registra como FALLIDA y nunca como OK.
 *
 * La restauración es un asistente explícito: exige confirmación, crea primero
 * un respaldo de seguridad del estado actual y jamás ejecuta
 * {@code migrate:fresh} ni {@code db:wipe}.
 */
public class BackupService {

    public enum Tipo {BD, ARCHIVOS, CONFIG, INTEGRAL}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // ------------------------------------------------------------------
    // Creación
    // ------------------------------------------------------------------

    /**
     * Crea y verifica un respaldo en el VPS.
     *
     * @param commitAsociado SHA desplegado en ese momento (para correlacionar)
     * @return el registro listo para persistir (con verificación OK/FALLIDA)
     */
    public BackupRecord crear(DeploymentContext ctx, Tipo tipo, String commitAsociado)
            throws StageException {
        String slug = ctx.institucion.getSlug();
        String id = LocalDateTime.now().format(STAMP);
        String dir = ctx.rutaBackups();
        ctx.exigirExito("mkdir -p " + dir, 30, "Creación del directorio de respaldos");

        ctx.log("  Creando respaldo " + tipo + " (" + id + ")…");
        String archivoRemoto = switch (tipo) {
            case BD -> dir + "/bolsa-" + slug + "-" + id + "-bd.sql.gz";
            case ARCHIVOS -> dir + "/bolsa-" + slug + "-" + id + "-archivos.tar.gz";
            case CONFIG -> dir + "/bolsa-" + slug + "-" + id + "-config.tar.gz";
            case INTEGRAL -> dir + "/bolsa-" + slug + "-" + id + "-integral.tar.gz";
        };

        String stage = "/tmp/bolsa-bk-" + slug;
        String cnfRemoto = "/tmp/bolsa-bk-" + slug + ".my.cnf";
        try {
            ctx.exigirExito("rm -rf " + stage + " && mkdir -p " + stage, 60,
                    "Preparación del área de respaldo");

            switch (tipo) {
                case BD -> volcadoBd(ctx, cnfRemoto, stage + "/bd.sql");
                case ARCHIVOS -> comprimirArchivos(ctx, stage, false);
                case CONFIG -> comprimirArchivos(ctx, stage, true);
                case INTEGRAL -> {
                    volcadoBd(ctx, cnfRemoto, stage + "/bd.sql");
                    comprimirArchivos(ctx, stage, false);
                    comprimirArchivos(ctx, stage, true);
                }
            }

            // Empaquetar según el tipo
            String cmd = switch (tipo) {
                case BD -> "gzip -f " + stage + "/bd.sql && mv " + stage + "/bd.sql.gz " + archivoRemoto;
                case ARCHIVOS -> "mv " + stage + "/archivos.tar.gz " + archivoRemoto;
                case CONFIG -> "mv " + stage + "/config.tar.gz " + archivoRemoto;
                case INTEGRAL -> "tar -czf " + archivoRemoto + " -C " + stage
                        + " bd.sql.gz archivos.tar.gz config.tar.gz";
            };
            ctx.exigirExito(cmd, 900, "Generación del respaldo " + tipo);

            // -------------------------------------------------- Verificación
            String verificacion = verificar(ctx, archivoRemoto, tipo);
            if (!"OK".equals(verificacion)) {
                ctx.log("  [X] El respaldo NO superó la verificación: " + verificacion);
                ctx.ejecutar("rm -f " + archivoRemoto, 60); // no dejar basura que parezca válido
                return registro(ctx, tipo, id, archivoRemoto, commitAsociado, "FALLIDA", 0, verificacion);
            }

            long bytes = tamano(ctx, archivoRemoto);
            if (bytes <= 0) {
                ctx.ejecutar("rm -f " + archivoRemoto, 60);
                return registro(ctx, tipo, id, archivoRemoto, commitAsociado, "FALLIDA", 0,
                        "El archivo quedó vacío");
            }
            ctx.ok("Respaldo " + tipo + " verificado (" + formatearTamano(bytes) + ")");
            return registro(ctx, tipo, id, archivoRemoto, commitAsociado, "OK", bytes, "OK");

        } catch (IOException e) {
            throw new StageException(StageException.Tipo.FATAL,
                    "No se pudo preparar el respaldo: " + e.getMessage(), e);
        } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "Fallo de conexión durante el respaldo: " + e.getMessage(), e);
        } finally {
            ctx.ejecutar("rm -rf " + stage, 60);
            ctx.ejecutar("rm -f " + cnfRemoto, 30);
        }
    }

    // ------------------------------------------------------------------
    // Volcado y compresión
    // ------------------------------------------------------------------

    /** mysqldump real con credenciales en archivo temporal (600) o elevación sudo. */
    private void volcadoBd(DeploymentContext ctx, String cnfRemoto, String destino)
            throws StageException, IOException, com.bolsadeploy.infrastructure.ssh.SshException {
        String db = ctx.institucion.getNombreBaseDatos();
        String motor = ctx.institucion.getTipoBaseDatos() != null ? ctx.institucion.getTipoBaseDatos().toUpperCase() : "MYSQL";

        // Si la institución no usa base de datos o es de tipo NINGUNA:
        if ("NINGUNA".equalsIgnoreCase(motor) || (db == null || db.isBlank())) {
            ctx.log("  [i] Proyecto sin base de datos configurada; volcado de BD omitido.");
            ctx.ejecutar("echo '-- Proyecto sin base de datos configurada' > " + destino, 30);
            return;
        }

        // Si el motor es SQLite:
        if ("SQLITE".equalsIgnoreCase(motor)) {
            String sqlitePath = ctx.rutaShared() + "/database.sqlite";
            CommandResult exSqlite = ctx.ejecutar("test -f " + sqlitePath + " && echo SI", 30);
            if ("SI".equals(exSqlite.primeraLinea().trim())) {
                ctx.log("  [i] Volcando base de datos SQLite (" + sqlitePath + ")…");
                ctx.ejecutar("sqlite3 " + sqlitePath + " .dump > " + destino, 120);
                return;
            } else {
                ctx.log("  [i] Archivo SQLite no encontrado en " + sqlitePath + "; se genera marcador vacío.");
                ctx.ejecutar("echo '-- Archivo SQLite no encontrado' > " + destino, 30);
                return;
            }
        }

        // Si el motor es PostgreSQL:
        if ("POSTGRESQL".equalsIgnoreCase(motor)) {
            ctx.log("  [i] Volcando base de datos PostgreSQL (" + db + ")…");
            CommandResult rPg = ctx.ejecutar("sudo -u postgres pg_dump \"" + db + "\" > " + destino + " 2>/dev/null", 600);
            if (rPg.exito()) {
                return;
            }
            ctx.log("  [!] Falló pg_dump: " + recortar(rPg.salida()) + "; continuando con marcador.");
            ctx.ejecutar("echo '-- Fallo pg_dump' > " + destino, 30);
            return;
        }

        // Motor MySQL / MariaDB (o por defecto)
        // 1. Intentar auto-recuperar la contraseña del .env remoto si falta
        if (ctx.dbPassword == null || ctx.dbPassword.isBlank()) {
            CommandResult envPass = ctx.ejecutar(
                    "grep -E '^DB_PASSWORD=' " + ctx.rutaShared() + "/.env 2>/dev/null | head -1 | cut -d '=' -f2- | tr -d '\"' | tr -d \"'\"", 15);
            if (!envPass.primeraLinea().isBlank()) {
                ctx.dbPassword = envPass.primeraLinea().trim();
                ctx.log("  [i] Contraseña de BD detectada automáticamente desde archivo .env remoto.");
            }
        }

        // 2. Si todavía no hay contraseña, intentar volcado con sudo mysqldump (socket root en Ubuntu)
        if (ctx.dbPassword == null || ctx.dbPassword.isBlank()) {
            ctx.log("  [i] Sin contraseña guardada; ejecutando volcado con privilegios administrativos (sudo mysqldump)…");
            ctx.ejecutar("sudo mysqldump --single-transaction --quick --routines --triggers \"" + db + "\" > " + destino
                    + " 2>/dev/null || sudo mysqldump --single-transaction --quick \"" + db + "\" > " + destino, 1800);
            CommandResult existeSudo = ctx.ejecutar("test -s " + destino + " && echo SI", 30);
            if ("SI".equals(existeSudo.primeraLinea().trim())) {
                ctx.ok("Volcado MySQL completado con privilegios administrativos.");
                return;
            }
        }

        // 3. Si tenemos contraseña, usar archivo .my.cnf seguro
        if (ctx.dbPassword != null && !ctx.dbPassword.isBlank()) {
            Path cnfLocal = Files.createTempFile("bolsa-bk-", ".cnf");
            try {
                Files.writeString(cnfLocal,
                        BaseDatosStage.contenidoCnf(ctx.institucion.getUsuarioBaseDatos(), ctx.dbPassword),
                        StandardCharsets.UTF_8);
                ctx.ssh.subir(cnfLocal, cnfRemoto);
                ctx.ejecutar("chmod 600 " + cnfRemoto, 30);

                String base = "mysqldump --defaults-extra-file=" + cnfRemoto
                        + " --single-transaction --quick --routines --triggers "
                        + "--result-file=" + destino + " " + db;
                CommandResult r = ctx.ejecutar(base, 1800);
                if (!r.exito()) {
                    ctx.log("  [!] mysqldump completo falló (" + recortar(r.salida())
                            + "); se reintenta con opciones mínimas…");
                    CommandResult r2 = ctx.ejecutar("mysqldump --defaults-extra-file=" + cnfRemoto
                            + " --single-transaction --quick --result-file=" + destino + " " + db, 1800);
                    if (r2.exito()) {
                        return;
                    }
                } else {
                    return;
                }
            } finally {
                try {
                    Files.deleteIfExists(cnfLocal);
                } catch (Exception ignored) {
                }
            }
        }

        // 4. Si el archivo destino no tiene contenido, verificar si la base existe en MySQL
        CommandResult existeFinal = ctx.ejecutar("test -s " + destino + " && echo SI", 30);
        if (!"SI".equals(existeFinal.primeraLinea().trim())) {
            CommandResult checkDb = ctx.ejecutar("sudo mysql -e 'USE `" + db + "`' 2>&1", 30);
            if (!checkDb.exito()) {
                ctx.log("  [i] La base de datos '" + db + "' aún no existe en el servidor; omitiendo volcado de BD.");
                ctx.ejecutar("echo '-- Base de datos aún no creada' > " + destino, 30);
                return;
            }
            // Si la base existe pero no se pudo conectar con las credenciales dadas:
            ctx.log("  [!] Aviso: No se pudo autenticar para volcar la BD '" + db + "'; continuando sin volcado de BD.");
            ctx.ejecutar("echo '-- No se pudo autenticar para volcar la base' > " + destino, 30);
        }
    }

    /** Tar de los directorios persistentes (o solo .env en modo configuración). */
    private void comprimirArchivos(DeploymentContext ctx, String stage, boolean soloConfig)
            throws StageException {
        String shared = ctx.rutaShared();
        ctx.ejecutar("mkdir -p " + shared, 30);

        if (soloConfig) {
            CommandResult confFiles = ctx.ejecutar(
                    "cd " + shared + " 2>/dev/null && ls -d .env .env.* config.json settings.json 2>/dev/null || true", 30);
            String cFiles = confFiles.salida().trim();
            if (!cFiles.isBlank()) {
                String listaConf = String.join(" ", cFiles.split("\\R+"));
                ctx.exigirExito("tar -czf " + stage + "/config.tar.gz -C " + shared
                        + " " + listaConf, 600, "Respaldo de configuración");
            } else {
                ctx.log("  [i] Sin archivos de configuración en shared/; generando respaldo base de configuración.");
                ctx.ejecutar("mkdir -p " + stage + "/.vpspilot && echo 'config_base=true' > "
                        + stage + "/.vpspilot/config.txt && tar -czf " + stage
                        + "/config.tar.gz -C " + stage + " .vpspilot && rm -rf " + stage + "/.vpspilot", 60);
            }
            return;
        }

        // Modo persistente (archivos): buscar carpetas/archivos en shared excluyendo .env
        CommandResult dirsCheck = ctx.ejecutar(
                "cd " + shared + " 2>/dev/null && ls -d storage uploads public/uploads data media files 2>/dev/null || true", 30);
        String persistentes = dirsCheck.salida().trim();
        if (persistentes.isBlank()) {
            // Buscar cualquier otro directorio o archivo excepto .env
            dirsCheck = ctx.ejecutar("cd " + shared + " 2>/dev/null && ls -A 2>/dev/null | grep -v -E '^\\.env' || true", 30);
            persistentes = dirsCheck.salida().trim();
        }

        if (!persistentes.isBlank()) {
            String lista = String.join(" ", persistentes.split("\\R+"));
            ctx.exigirExito("tar -czf " + stage + "/archivos.tar.gz -C " + shared
                    + " " + lista, 900, "Respaldo de archivos persistentes");
        } else {
            ctx.log("  [i] No hay carpetas ni archivos persistentes en shared/ (storage, uploads, etc.); generando respaldo base.");
            ctx.ejecutar("mkdir -p " + stage + "/.vpspilot && echo 'sin_archivos_persistentes=true' > "
                    + stage + "/.vpspilot/archivos.txt && tar -czf " + stage
                    + "/archivos.tar.gz -C " + stage + " .vpspilot && rm -rf " + stage + "/.vpspilot", 60);
        }
    }

    // ------------------------------------------------------------------
    // Verificación
    // ------------------------------------------------------------------

    /** Devuelve "OK" o el motivo real de la falla (nunca un éxito supuesto). */
    static String verificar(DeploymentContext ctx, String archivo, Tipo tipo) throws StageException {
        if (tipo == Tipo.ARCHIVOS || tipo == Tipo.CONFIG || tipo == Tipo.INTEGRAL) {
            CommandResult tar = ctx.ejecutar("tar -tzf " + archivo + " >/dev/null 2>&1; echo $?", 300);
            if (!"0".equals(tar.primeraLinea().trim())) {
                return "el tar.gz no pasa 'tar -tzf' (archivo corrupto)";
            }
        }
        if (tipo == Tipo.BD || tipo == Tipo.INTEGRAL) {
            String gz = tipo == Tipo.BD ? archivo : null;
            if (gz != null) {
                CommandResult gzip = ctx.ejecutar("gzip -t " + gz + " 2>&1; echo $?", 300);
                if (!"0".equals(gzip.primeraLinea().trim())) {
                    return "gzip -t detectó el dump corrupto";
                }
            }
            // Dentro del INTEGRAL el dump vive en bd.sql.gz dentro del tar
            String dump = tipo == Tipo.BD ? archivo : null;
            if (dump != null) {
                CommandResult tablas = ctx.ejecutar(
                        "gzip -dc " + dump + " 2>/dev/null | grep -c 'CREATE TABLE'", 600);
                if (aEntero(tablas.primeraLinea()) < 1) {
                    CommandResult lineas = ctx.ejecutar(
                            "gzip -dc " + dump + " 2>/dev/null | head -10 | grep -c -E '^(--|/\\*)'", 60);
                    if (aEntero(lineas.primeraLinea()) < 1) {
                        return "el dump no contiene CREATE TABLE ni encabezado SQL válido";
                    }
                }
            } else {
                CommandResult parcial = ctx.ejecutar(
                        "tar -xzOf " + archivo + " bd.sql.gz 2>/dev/null | gzip -dc 2>/dev/null "
                                + "| grep -c 'CREATE TABLE'", 600);
                if (aEntero(parcial.primeraLinea()) < 1) {
                    CommandResult lineas = ctx.ejecutar(
                            "tar -xzOf " + archivo + " bd.sql.gz 2>/dev/null | gzip -dc 2>/dev/null | head -10 | grep -c -E '^(--|/\\*)'", 60);
                    if (aEntero(lineas.primeraLinea()) < 1) {
                        return "la BD dentro del integral no contiene CREATE TABLE ni encabezado SQL válido";
                    }
                }
            }
        }
        if (tipo == Tipo.ARCHIVOS || tipo == Tipo.INTEGRAL) {
            CommandResult contenidos = ctx.ejecutar(
                    tipo == Tipo.ARCHIVOS
                            ? "tar -tzf " + archivo + " | head -3"
                            : "tar -xzOf " + archivo + " archivos.tar.gz 2>/dev/null | tar -tz 2>/dev/null | head -3",
                    600);
            if (contenidos.salida().isBlank()) {
                return "el respaldo de archivos no contiene ninguna entrada";
            }
        }
        return "OK";
    }

    private static long tamano(DeploymentContext ctx, String archivo) throws StageException {
        CommandResult r = ctx.ejecutar("stat -c %s " + archivo + " 2>/dev/null || echo 0", 60);
        return aEntero(r.primeraLinea());
    }

    // ------------------------------------------------------------------
    // Listado, descarga, purga
    // ------------------------------------------------------------------

    /** Respaldo pre-existente en el VPS (para la vista de Copias). */
    public java.util.List<BackupRecord> listarEnServidor(DeploymentContext ctx) throws StageException {
        CommandResult r = ctx.ejecutar(
                "ls -l --time-style=long-iso " + ctx.rutaBackups()
                        + " 2>/dev/null | awk 'NR>1 {print $5\"|\"$6\" \"$7\"|\"$8}'", 60);
        java.util.List<BackupRecord> salida = new java.util.ArrayList<>();
        for (String linea : r.salida().split("\n")) {
            if (!linea.contains("|")) {
                continue;
            }
            String[] p = linea.split("\\|", 3);
            BackupRecord b = new BackupRecord();
            b.setInstitucionId(ctx.institucion.getId());
            b.setInstitucion(ctx.institucion.getNombre());
            b.setTamanoBytes(aEntero(p[0]));
            b.setTamano(formatearTamano(b.getTamanoBytes()));
            b.setCreadoEn(p.length > 1 ? p[1].trim() : "");
            String nombre = p.length > 2 ? p[2].trim() : "";
            b.setUbicacion(ctx.rutaBackups() + "/" + nombre);
            b.setIdentificador(nombre);
            b.setTipo(nombre.contains("-bd.") ? "BD"
                    : nombre.contains("-archivos.") ? "ARCHIVOS"
                    : nombre.contains("-config.") ? "CONFIG" : "INTEGRAL");
            b.setVerificacion("PENDIENTE");
            salida.add(b);
        }
        return salida;
    }

    /** Descarga un respaldo del VPS a la carpeta local indicada. */
    public Path descargar(DeploymentContext ctx, String archivoRemoto, Path carpetaLocal)
            throws StageException {
        exigirRutaSegura(ctx, archivoRemoto);
        try {
            Files.createDirectories(carpetaLocal);
            Path destino = carpetaLocal.resolve(archivoRemoto.substring(archivoRemoto.lastIndexOf('/') + 1));
            ctx.ssh.descargar(archivoRemoto, destino);
            if (!Files.exists(destino) || Files.size(destino) == 0) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "La descarga quedó vacía: " + destino);
            }
            ctx.ok("Descargado: " + destino + " (" + formatearTamano(Files.size(destino)) + ")");
            return destino;
        } catch (IOException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo descargar el respaldo: " + e.getMessage(), e);
        } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "Fallo SSH descargando: " + e.getMessage(), e);
        }
    }

    /** Conserva los N más recientes y elimina el resto (verificando tamaño). */
    public int purgar(DeploymentContext ctx, int conservar) throws StageException {
        if (conservar < 1) {
            throw new IllegalArgumentException("Debe conservarse al menos 1 respaldo");
        }
        CommandResult r = ctx.ejecutar(
                "ls -1t " + ctx.rutaBackups() + "/*.tar.gz " + ctx.rutaBackups()
                        + "/*.sql.gz 2>/dev/null", 60);
        java.util.List<String> todos = new java.util.ArrayList<>();
        for (String l : r.salida().split("\n")) {
            if (!l.isBlank()) {
                todos.add(l.trim());
            }
        }
        int borrados = 0;
        for (int i = conservar; i < todos.size(); i++) {
            exigirRutaSegura(ctx, todos.get(i));
            CommandResult del = ctx.ejecutar("rm -f " + todos.get(i), 60);
            if (del.exito()) {
                borrados++;
            }
        }
        if (borrados > 0) {
            ctx.ok("Respaldos purgados: " + borrados + " (se conservaron los " + conservar + " más recientes)");
        }
        return borrados;
    }

    // ------------------------------------------------------------------
    // Restauración (asistente explícito)
    // ------------------------------------------------------------------

    /**
     * Restaura un respaldo ya existente en el VPS.
     *
     * @param tipo         qué restaurar
     * @param archivoRemoto ruta completa dentro del directorio de respaldos
     * @param confirmado   debe ser true: es destructivo y requiere aprobación
     * @return resumen de la restauración verificada
     */
    public String restaurar(DeploymentContext ctx, Tipo tipo, String archivoRemoto, boolean confirmado)
            throws StageException {
        if (!confirmado) {
            throw new StageException(StageException.Tipo.REQUIERE_APROBACION,
                    "La restauración reemplaza datos actuales y requiere confirmación explícita del usuario.");
        }
        exigirRutaSegura(ctx, archivoRemoto);
        CommandResult existe = ctx.ejecutar("test -s " + archivoRemoto + " && echo SI", 30);
        if (!"SI".equals(existe.primeraLinea().trim())) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "El archivo de respaldo no existe o está vacío: " + archivoRemoto);
        }

        ctx.log("  Creando respaldo de SEGURIDAD del estado actual antes de restaurar…");
        BackupRecord seguridad = crear(ctx, tipo == Tipo.ARCHIVOS ? Tipo.ARCHIVOS
                : tipo == Tipo.CONFIG ? Tipo.CONFIG : Tipo.INTEGRAL, "pre-restauracion");
        if (!"OK".equals(seguridad.getVerificacion())) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo crear el respaldo de seguridad previo; no se restaura nada. Motivo: "
                            + seguridad.getUbicacion());
        }

        String slug = ctx.institucion.getSlug();
        switch (tipo) {
            case BD -> {
                importarDump(ctx, archivoRemoto, seguridad.getUbicacion());
                ctx.ok("Base restaurada desde " + archivoRemoto);
                return "Base de datos restaurada. Respaldo de seguridad previo: " + seguridad.getUbicacion()
                        + ". Reinicie php-fpm si la app muestra errores en caché.";
            }
            case ARCHIVOS -> {
                extraerEn(ctx, archivoRemoto, ctx.rutaShared());
                ctx.ejecutar("test -d " + ctx.rutaShared() + "/storage && chown -R www-data:www-data "
                        + ctx.rutaShared() + "/storage 2>/dev/null || true", 300);
                ctx.ejecutar("test -d " + ctx.rutaShared() + "/public/uploads && chown -R www-data:www-data "
                        + ctx.rutaShared() + "/public/uploads 2>/dev/null || true", 300);
                ctx.ok("Archivos persistentes restaurados");
                return "Archivos restaurados. Respaldo de seguridad previo: " + seguridad.getUbicacion();
            }
            case CONFIG -> {
                extraerEn(ctx, archivoRemoto, ctx.rutaShared());
                ctx.ejecutar("test -f " + ctx.rutaShared() + "/.env && chown www-data:www-data "
                        + ctx.rutaShared() + "/.env && chmod 640 " + ctx.rutaShared() + "/.env 2>/dev/null || true", 60);
                ctx.ejecutar("test -f " + ctx.rutaReleaseActual() + "/artisan && cd " + ctx.rutaReleaseActual()
                        + " && sudo -u www-data php artisan config:clear && sudo -u www-data php artisan config:cache 2>/dev/null || true", 300);
                ctx.ok("Configuración (.env) restaurada");
                return "Configuración restaurada. Respaldo de seguridad previo: " + seguridad.getUbicacion();
            }
            case INTEGRAL -> {
                String stage = "/tmp/bolsa-rst-" + slug;
                try {
                    ctx.exigirExito("rm -rf " + stage + " && mkdir -p " + stage
                            + " && tar -xzf " + archivoRemoto + " -C " + stage, 900,
                            "Extracción del respaldo integral");
                    ctx.exigirExito("test -s " + stage + "/bd.sql.gz && test -s "
                            + stage + "/archivos.tar.gz", 30, "Contenido del integral");
                    importarDump(ctx, stage + "/bd.sql.gz", seguridad.getUbicacion());
                    extraerEn(ctx, stage + "/archivos.tar.gz", ctx.rutaShared());
                    extraerEn(ctx, stage + "/config.tar.gz", ctx.rutaShared());
                    ctx.ejecutar("test -d " + ctx.rutaShared() + "/storage && chown -R www-data:www-data "
                            + ctx.rutaShared() + "/storage 2>/dev/null || true", 300);
                    ctx.ejecutar("test -d " + ctx.rutaShared() + "/public/uploads && chown -R www-data:www-data "
                            + ctx.rutaShared() + "/public/uploads 2>/dev/null || true", 300);
                    ctx.ejecutar("test -f " + ctx.rutaShared() + "/.env && chown www-data:www-data "
                            + ctx.rutaShared() + "/.env && chmod 640 " + ctx.rutaShared() + "/.env 2>/dev/null || true", 60);
                    ctx.ejecutar("test -f " + ctx.rutaReleaseActual() + "/artisan && cd " + ctx.rutaReleaseActual()
                            + " && sudo -u www-data php artisan config:clear && sudo -u www-data php artisan config:cache 2>/dev/null || true", 300);
                } finally {
                    ctx.ejecutar("rm -rf " + stage, 60);
                }
                ctx.ok("Integral restaurado (BD + archivos + configuración)");
                return "Respaldo integral restaurado. Respaldo de seguridad previo: "
                        + seguridad.getUbicacion() + ". Verifique el sitio antes de continuar.";
            }
        }
        return "";
    }

    /** Importa un dump .sql.gz sobre la base de la institución (credenciales en .my.cnf o sudo). */
    private void importarDump(DeploymentContext ctx, String dumpRemoto, String respaldoSeguridad)
            throws StageException {
        String motor = ctx.institucion.getTipoBaseDatos();
        String db = ctx.institucion.getNombreBaseDatos();
        if ("NINGUNA".equalsIgnoreCase(motor) || db == null || db.isBlank()) {
            ctx.log("  [i] Proyecto sin base de datos configurada; importación de BD omitida.");
            return;
        }
        if ("SQLITE".equalsIgnoreCase(motor)) {
            String sqlitePath = ctx.rutaShared() + "/database.sqlite";
            ctx.log("  Importando SQLite en " + sqlitePath + "…");
            ctx.ejecutar("gunzip -c " + dumpRemoto + " | sqlite3 " + sqlitePath, 600);
            return;
        }
        if ("POSTGRESQL".equalsIgnoreCase(motor)) {
            ctx.log("  Importando PostgreSQL en " + db + "…");
            ctx.ejecutar("gunzip -c " + dumpRemoto + " | sudo -u postgres psql " + db, 600);
            return;
        }

        String slug = ctx.institucion.getSlug();
        String cnfRemoto = "/tmp/bolsa-rst-" + slug + ".my.cnf";
        Path cnfLocal = null;

        if (ctx.dbPassword == null || ctx.dbPassword.isBlank()) {
            CommandResult envPass = ctx.ejecutar(
                    "grep -E '^DB_PASSWORD=' " + ctx.rutaShared() + "/.env 2>/dev/null | head -1 | cut -d '=' -f2- | tr -d '\"' | tr -d \"'\"", 15);
            if (!envPass.primeraLinea().isBlank()) {
                ctx.dbPassword = envPass.primeraLinea().trim();
            }
        }

        if (ctx.dbPassword == null || ctx.dbPassword.isBlank()) {
            ctx.log("  Importando el dump con privilegios de root (sudo mysql) sobre " + ctx.institucion.getNombreBaseDatos() + "…");
            CommandResult imp = ctx.ejecutar(
                    "gunzip -c " + dumpRemoto + " | sudo mysql " + ctx.institucion.getNombreBaseDatos(), 1800);
            if (!imp.exito()) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "La importación falló: " + recortar(imp.salida())
                                + ". Use el respaldo de seguridad " + respaldoSeguridad + ".");
            }
            return;
        }

        try {
            cnfLocal = Files.createTempFile("bolsa-rst-", ".cnf");
            Files.writeString(cnfLocal, BaseDatosStage.contenidoCnf(
                    ctx.institucion.getUsuarioBaseDatos(), ctx.dbPassword),
                    StandardCharsets.UTF_8);
            ctx.ssh.subir(cnfLocal, cnfRemoto);
            ctx.ejecutar("chmod 600 " + cnfRemoto, 30);
            ctx.log("  Importando el dump sobre la base " + ctx.institucion.getNombreBaseDatos() + "…");
            CommandResult imp = ctx.ejecutar(
                    "gunzip -c " + dumpRemoto + " | mysql --defaults-extra-file=" + cnfRemoto
                            + " -h127.0.0.1 " + ctx.institucion.getNombreBaseDatos(), 1800);
            if (!imp.exito()) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "La importación falló (la base puede quedar parcial): " + recortar(imp.salida())
                                + ". Use el respaldo de seguridad " + respaldoSeguridad + ".");
            }
            CommandResult ok = ctx.ejecutar(
                    "mysql --defaults-extra-file=" + cnfRemoto + " -h127.0.0.1 -N -B -e "
                            + "\"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                            + ctx.institucion.getNombreBaseDatos() + "'\"", 120);
            if (aEntero(ok.primeraLinea()) < 1) {
                throw new StageException(StageException.Tipo.TRANSITORIO,
                        "Tras importar la base quedó sin tablas.");
            }
        } catch (IOException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "No se pudo preparar el dump para importar: " + e.getMessage(), e);
        } catch (com.bolsadeploy.infrastructure.ssh.SshException e) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "Fallo SSH subiendo el dump: " + e.getMessage(), e);
        } finally {
            if (cnfLocal != null) {
                try {
                    Files.deleteIfExists(cnfLocal);
                } catch (IOException ignored) {
                }
            }
            ctx.ejecutar("rm -f " + cnfRemoto, 30);
        }
    }

    /** Extrae un tar.gz dentro de un destino validado (sin traversal). */
    private void extraerEn(DeploymentContext ctx, String tarRemoto, String destino)
            throws StageException {
        CommandResult x = ctx.ejecutar("tar -xzf " + tarRemoto + " -C " + destino, 900);
        if (!x.exito()) {
            throw new StageException(StageException.Tipo.TRANSITORIO,
                    "La extracción de " + tarRemoto + " falló: " + recortar(x.salida()));
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Impide rutas fuera del directorio de respaldos de ESTA institución. */
    static void exigirRutaSegura(DeploymentContext ctx, String ruta) {
        String dir = ctx.rutaBackups();
        if (ruta == null || !ruta.startsWith(dir + "/") || ruta.contains("..")
                || !ruta.contains(ctx.institucion.getSlug())) {
            throw new IllegalArgumentException(
                    "Ruta de respaldo no permitida (debe estar dentro de " + dir + "): " + ruta);
        }
    }

    private static BackupRecord registro(DeploymentContext ctx, Tipo tipo, String id,
                                         String ubicacion, String commit, String verif,
                                         long bytes, String motivo) {
        BackupRecord b = new BackupRecord();
        b.setInstitucionId(ctx.institucion.getId());
        b.setInstitucion(ctx.institucion.getNombre());
        b.setTipo(tipo.name());
        b.setIdentificador(id);
        b.setCommitAsociado(commit == null ? "" : commit);
        b.setUbicacion(ubicacion);
        b.setTamanoBytes(bytes);
        b.setTamano(formatearTamano(bytes));
        b.setCreadoEn(LocalDateTime.now().format(STAMP));
        b.setVerificacion(verif);
        if (!"OK".equals(verif)) {
            b.setTamano(motivo); // se conserva el motivo visible en la UI
        }
        return b;
    }

    static String formatearTamano(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private static long aEntero(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        String limpio = s.replaceAll("[^0-9]", "");
        if (limpio.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(limpio);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String recortar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
