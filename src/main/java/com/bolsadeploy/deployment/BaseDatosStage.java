package com.bolsadeploy.deployment;

import com.bolsadeploy.security.CommandValidator;
import com.bolsadeploy.security.PasswordHasher;
import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Etapa 3 — Base de datos.
 *
 * Secuencia (idempotente):
 * <ol>
 *   <li>Sube un {@code .my.cnf} temporal con las credenciales de la aplicación
 *       (evita exponer la contraseña en la línea de comandos y en los logs).</li>
 *   <li>Crea la base y el usuario dedicado vía root solo si no existen.</li>
 *   <li>Importa {@code base_instalacion.sql} (estructura + datos de referencia,
 *       SIN datos de la institución de origen) solo si la base está vacía.</li>
 *   <li>Crea el administrador inicial (hash bcrypt coste 12 generado en el
 *       cliente, compatible con {@code password_verify} de PHP) si no hay
 *       usuarios.</li>
 *   <li>Fija el nombre real de la institución en {@code system_configuration}.</li>
 * </ol>
 *
 * Nunca ejecuta {@code db:seed}, nunca importa el dump completo y nunca usa
 * {@code migrate:fresh}.
 */
public class BaseDatosStage implements DeploymentStage {

    @Override
    public String id() {
        return "base-datos";
    }

    @Override
    public String nombre() {
        return "Base de datos (crear, importar esquema, administrador)";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        return false; // siempre se revisa: es barato y garantiza el estado
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        if (!ctx.configurarBaseDatos) {
            ctx.log("  [i] Configuración de base de datos omitida (desactivada para este proyecto).");
            return;
        }

        String db = ctx.institucion.getNombreBaseDatos();
        String usuario = ctx.institucion.getUsuarioBaseDatos();
        String password = ctx.dbPassword;

        // ------------------------------------------------------- Validaciones
        if (db == null || db.isBlank() || usuario == null || usuario.isBlank()) {
            ctx.log("  [i] No se indicó base de datos ni usuario; etapa omitida.");
            return;
        }
        try {
            CommandValidator.exigirNombreSql(db);
            CommandValidator.exigirNombreSql(usuario);
        } catch (IllegalArgumentException e) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, e.getMessage());
        }
        if (password == null || password.isBlank()) {
            password = "pwd" + Math.abs((db + usuario).hashCode()) + "!";
        }

        String cnfRemoto = "/tmp/vpspilot-" + ctx.institucion.getSlug() + ".my.cnf";
        Path cnfLocal = null;
        Path sqlLocal = null;
        try {
            // ------------------------------------------------- Credenciales seguras
            cnfLocal = escribirTemp("vpspilot-mycnf-", ".cnf", contenidoCnf(usuario, password));
            ctx.ssh.subir(cnfLocal, cnfRemoto);
            ctx.ejecutar("chmod 600 " + cnfRemoto, 30);
            String q = "mysql --defaults-extra-file=" + cnfRemoto + " -h127.0.0.1 -N -B ";

            // ¿Existe ya la base y el usuario?
            CommandResult conexion = ctx.ejecutar(q + "-e \"SELECT 1\"", 60);
            if (conexion.exito()) {
                ctx.log("  Base '" + db + "' y usuario '" + usuario + "' ya existen; se reutilizan.");
            } else {
                crearBase(ctx, db, usuario, password);
                CommandResult trasCrear = ctx.ejecutar(q + "-e \"SELECT 1\"", 60);
                if (!trasCrear.exito()) {
                    throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                            "La base se creó pero el usuario '" + usuario + "' no pudo conectarse: "
                                    + limpiar(trasCrear.salida()));
                }
                ctx.ok("Base '" + db + "' y usuario dedicado '" + usuario + "' creados");
            }

            // ------------------------------------------------- Importar esquema
            long tablas = aEntero(ctx.ejecutar(
                    q + "-e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + db + "'\"",
                    60).primeraLinea());
            boolean debeImportar = ctx.importarEsquemaBase || "true".equals(ctx.valores.get("importarBaseSql"));
            if (tablas == 0 && debeImportar) {
                ctx.log("  Base vacía: importando esquema base…");
                Path baseSql = recursoTemp("/db/base_instalacion.sql", "base-instalacion-", ".sql");
                sqlLocal = baseSql;
                String remotaSql = "/tmp/vpspilot-" + ctx.institucion.getSlug() + "-base.sql";
                try {
                    ctx.ssh.subir(baseSql, remotaSql);
                    CommandResult importar = ctx.ejecutar(
                            q + "--default-character-set=utf8mb4 " + db + " < " + remotaSql, 600);
                    if (!importar.exito()) {
                        ctx.log("  [!] Aviso al importar esquema: " + limpiar(importar.salida()));
                    } else {
                        ctx.ok("Esquema inicial importado exitosamente");
                        ctx.valores.put("esquemaImportado", "true");
                    }
                } finally {
                    ctx.ejecutar("rm -f " + remotaSql, 30);
                }
            } else if (tablas == 0) {
                ctx.log("  [i] Base de datos vacía lista para las migraciones del proyecto.");
                ctx.valores.put("esquemaImportado", "false");
            } else {
                ctx.log("  Esquema ya presente (" + tablas + " tablas); no se modifica.");
                ctx.valores.put("esquemaImportado", "false");
            }

            // ---------------------------------------------------- Administrador
            CommandResult tablaUser = ctx.ejecutar(
                    q + "-e \"SELECT table_name FROM information_schema.tables WHERE table_schema='"
                            + db + "' AND table_name IN ('user', 'users') LIMIT 1\"", 60);
            String nombreTablaUser = tablaUser.primeraLinea().trim();
            if (!nombreTablaUser.isBlank() && "user".equalsIgnoreCase(nombreTablaUser)) {
                long usuarios = aEntero(ctx.ejecutar(q + db + " -N -B -e \"SELECT COUNT(*) FROM user\"", 60).primeraLinea());
                if (usuarios == 0 && ctx.adminEmail != null && !ctx.adminEmail.isBlank()
                        && ctx.adminPassword != null && !ctx.adminPassword.isBlank() && CommandValidator.esEmail(ctx.adminEmail)) {
                    String hash = PasswordHasher.hash(ctx.adminPassword);
                    String nombre = ctx.adminNombre == null || ctx.adminNombre.isBlank() ? "Administrador" : ctx.adminNombre;
                    String adminSql = sqlAdministrador(nombre, ctx.adminEmail, hash);
                    Path adminLocal = escribirTemp("vpspilot-admin-", ".sql", adminSql);
                    String remotaAdmin = "/tmp/vpspilot-" + ctx.institucion.getSlug() + "-admin.sql";
                    try {
                        ctx.ssh.subir(adminLocal, remotaAdmin);
                        ctx.ejecutar(q + db + " < " + remotaAdmin, 120);
                        ctx.ok("Administrador inicial creado: " + ctx.adminEmail);
                    } finally {
                        Files.deleteIfExists(adminLocal);
                        ctx.ejecutar("rm -f " + remotaAdmin, 30);
                    }
                }
            } else {
                ctx.log("  [i] Gestión de usuarios administradores delegada al repositorio.");
            }

            // ------------------------------------------- Nombre de la institución
            CommandResult tablaCfg = ctx.ejecutar(
                    q + "-e \"SELECT count(*) FROM information_schema.tables WHERE table_schema='"
                            + db + "' AND table_name='system_configuration'\"", 60);
            if (aEntero(tablaCfg.primeraLinea().trim()) > 0) {
                String nombreInst = ctx.institucion.getNombre();
                String cfgSql = "UPDATE `system_configuration` SET `value`='" + CommandValidator.escaparSql(nombreInst)
                        + "', `updated_at`=NOW() WHERE `key`='application_name';";
                Path cfgLocal = escribirTemp("vpspilot-cfg-", ".sql", cfgSql);
                String remotaCfg = "/tmp/vpspilot-" + ctx.institucion.getSlug() + "-cfg.sql";
                try {
                    ctx.ssh.subir(cfgLocal, remotaCfg);
                    ctx.ejecutar(q + db + " < " + remotaCfg, 60);
                    ctx.ok("system_configuration.application_name = \"" + nombreInst + "\"");
                } finally {
                    Files.deleteIfExists(cfgLocal);
                    ctx.ejecutar("rm -f " + remotaCfg, 30);
                }
            }

        } catch (StageException e) {
            throw e;
        } catch (Exception e) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Error preparando la base de datos: " + e.getMessage(), e);
        } finally {
            ctx.ejecutar("rm -f " + cnfRemoto, 30); // las credenciales no quedan en el servidor
            borrar(cnfLocal);
            borrar(sqlLocal);
        }
    }

    // ------------------------------------------------------------------

    /** Crea base + usuario dedicado (ambos hosts de conexión) como root. */
    private void crearBase(DeploymentContext ctx, String db, String usuario, String password)
            throws StageException, IOException, com.bolsadeploy.infrastructure.ssh.SshException {
        String sql = "CREATE DATABASE IF NOT EXISTS `" + db
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
                + "CREATE USER IF NOT EXISTS '" + usuario + "'@'localhost' IDENTIFIED BY '"
                + CommandValidator.escaparSql(password) + "';\n"
                + "ALTER USER '" + usuario + "'@'localhost' IDENTIFIED BY '"
                + CommandValidator.escaparSql(password) + "';\n"
                + "CREATE USER IF NOT EXISTS '" + usuario + "'@'127.0.0.1' IDENTIFIED BY '"
                + CommandValidator.escaparSql(password) + "';\n"
                + "ALTER USER '" + usuario + "'@'127.0.0.1' IDENTIFIED BY '"
                + CommandValidator.escaparSql(password) + "';\n"
                + "GRANT ALL PRIVILEGES ON `" + db + "`.* TO '" + usuario + "'@'localhost';\n"
                + "GRANT ALL PRIVILEGES ON `" + db + "`.* TO '" + usuario + "'@'127.0.0.1';\n"
                + "FLUSH PRIVILEGES;\n";
        Path local = escribirTemp("bolsa-setup-", ".sql", sql);
        String remota = "/tmp/bolsa-" + ctx.institucion.getSlug() + "-setup.sql";
        try {
            ctx.ssh.subir(local, remota);
            CommandResult r = ctx.ejecutar(ctx.sudo("mysql -uroot < " + remota), 120);
            if (!r.exito()) {
                throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                        "No se pudo crear la base como root (usando 'sudo mysql -uroot'): "
                                + limpiar(r.salida())
                                + ". Verifique que MySQL permita el acceso root local por socket "
                                + "(configuración por defecto de Ubuntu).");
            }
        } finally {
            Files.deleteIfExists(local);
            ctx.ejecutar("rm -f " + remota, 30);
        }
    }

    /** INSERT del administrador inicial usando LAST_INSERT_ID() (sin ids fijos). */
    static String sqlAdministrador(String nombre, String email, String hash) {
        String n = CommandValidator.escaparSql(nombre);
        String e = CommandValidator.escaparSql(email);
        String h = CommandValidator.escaparSql(hash);
        return "INSERT INTO `person` (`document_type`, `document_number`, `names`, `phone`, `email`,"
                + " `created_at`, `updated_at`)\n"
                + "VALUES ('DNI', '00000000', '" + n + "', '00000000', '" + e + "', NOW(), NOW());\n"
                + "SET @pid = LAST_INSERT_ID();\n"
                + "INSERT INTO `user` (`company_id`, `person_id`, `rol_id`, `email`, `password`,"
                + " `is_active`, `attempts`, `created_at`, `updated_at`)\n"
                + "VALUES (NULL, @pid, 1, '" + e + "', '" + h + "', 1, 0, NOW(), NOW());\n"
                + "SET @uid = LAST_INSERT_ID();\n"
                + "INSERT INTO `rol_user` (`rol_id`, `user_id`) VALUES (1, @uid);\n";
    }

    /** Contenido del .my.cnf temporal (credenciales nunca en la línea de comandos). */
    public static String contenidoCnf(String usuario, String password) {
        if (password.contains("\n") || password.contains("\r")) {
            throw new IllegalArgumentException("La contraseña de la base no puede contener saltos de línea");
        }
        String valor = password.contains("'") && password.contains("\"")
                ? null
                : (password.contains("'") ? "\"" + password + "\"" : "'" + password + "'");
        if (valor == null) {
            throw new IllegalArgumentException(
                    "La contraseña de la base no puede contener comillas simples y dobles a la vez");
        }
        return "[client]\n"
                + "host=127.0.0.1\n"
                + "user=" + usuario + "\n"
                + "password=" + valor + "\n"
                + "default-character-set=utf8mb4\n";
    }

    // ------------------------------------------------------------------

    private static Path escribirTemp(String prefijo, String sufijo, String contenido) throws IOException {
        Path p = Files.createTempFile(prefijo, sufijo);
        Files.writeString(p, contenido, StandardCharsets.UTF_8);
        return p;
    }

    private static Path recursoTemp(String recurso, String prefijo, String sufijo) throws IOException {
        Path p = Files.createTempFile(prefijo, sufijo);
        try (InputStream in = BaseDatosStage.class.getResourceAsStream(recurso)) {
            if (in == null) {
                throw new IOException("Recurso no encontrado: " + recurso);
            }
            Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
        }
        return p;
    }

    private static void borrar(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }

    private static long aEntero(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        String limpio = s.replaceAll("[^0-9]", "");
        return limpio.isEmpty() ? -1 : Long.parseLong(limpio);
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 500 ? l.substring(0, 500) + "…" : l;
    }
}
