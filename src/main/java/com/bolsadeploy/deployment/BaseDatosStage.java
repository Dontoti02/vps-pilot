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
        String db = ctx.institucion.getNombreBaseDatos();
        String usuario = ctx.institucion.getUsuarioBaseDatos();
        String password = ctx.dbPassword;

        // ------------------------------------------------------- Validaciones
        if (db == null || db.isBlank() || usuario == null || usuario.isBlank()) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "Falta el nombre o el usuario de la base de datos en la institución.");
        }
        try {
            CommandValidator.exigirNombreSql(db);
            CommandValidator.exigirNombreSql(usuario);
        } catch (IllegalArgumentException e) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO, e.getMessage());
        }
        if (password == null || password.isBlank()) {
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "Falta la contraseña de la base de datos (no se guardó en el almacén de credenciales).");
        }

        String cnfRemoto = "/tmp/bolsa-" + ctx.institucion.getSlug() + ".my.cnf";
        Path cnfLocal = null;
        Path sqlLocal = null;
        try {
            // ------------------------------------------------- Credenciales seguras
            cnfLocal = escribirTemp("bolsa-mycnf-", ".cnf", contenidoCnf(usuario, password));
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
            if (tablas == 0) {
                ctx.log("  Base vacía: importando esquema base (30 tablas, solo datos de referencia)…");
                Path baseSql = recursoTemp("/db/base_instalacion.sql", "base-instalacion-", ".sql");
                sqlLocal = baseSql;
                String remotaSql = "/tmp/bolsa-" + ctx.institucion.getSlug() + "-base.sql";
                try {
                    ctx.ssh.subir(baseSql, remotaSql);
                    CommandResult importar = ctx.ejecutar(
                            q + "--default-character-set=utf8mb4 " + db + " < " + remotaSql, 600);
                    if (!importar.exito()) {
                        throw new StageException(StageException.Tipo.FATAL,
                                "Falló la importación de base_instalacion.sql: " + limpiar(importar.salida()));
                    }
                } finally {
                    ctx.ejecutar("rm -f " + remotaSql, 30);
                }
                tablas = aEntero(ctx.ejecutar(
                        q + "-e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + db + "'\"",
                        60).primeraLinea());
                if (tablas < 30) {
                    throw new StageException(StageException.Tipo.FATAL,
                            "La importación terminó pero solo hay " + tablas + " tablas (se esperaban 30).");
                }
                ctx.ok("Esquema importado: " + tablas + " tablas");
                ctx.valores.put("esquemaImportado", "true");
            } else {
                ctx.log("  Esquema ya presente (" + tablas + " tablas); no se reimporta.");
                ctx.valores.put("esquemaImportado", "false");
            }

            // ---------------------------------------------------- Administrador
            long usuarios = aEntero(ctx.ejecutar(q + db + " -N -B -e \"SELECT COUNT(*) FROM user\"",
                    60).primeraLinea());
            if (usuarios == 0) {
                if (ctx.adminEmail == null || ctx.adminEmail.isBlank()
                        || ctx.adminPassword == null || ctx.adminPassword.isBlank()) {
                    throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                            "Faltan el correo o la contraseña del administrador inicial.");
                }
                if (!CommandValidator.esEmail(ctx.adminEmail)) {
                    throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                            "El correo del administrador no es válido: " + ctx.adminEmail);
                }
                String hash = PasswordHasher.hash(ctx.adminPassword);
                String nombre = ctx.adminNombre == null || ctx.adminNombre.isBlank()
                        ? "Administrador" : ctx.adminNombre;
                String adminSql = sqlAdministrador(nombre, ctx.adminEmail, hash);
                Path adminLocal = escribirTemp("bolsa-admin-", ".sql", adminSql);
                String remotaAdmin = "/tmp/bolsa-" + ctx.institucion.getSlug() + "-admin.sql";
                try {
                    ctx.ssh.subir(adminLocal, remotaAdmin);
                    CommandResult ins = ctx.ejecutar(q + db + " < " + remotaAdmin, 120);
                    if (!ins.exito()) {
                        throw new StageException(StageException.Tipo.FATAL,
                                "No se pudo crear el administrador inicial: " + limpiar(ins.salida()));
                    }
                } finally {
                    Files.deleteIfExists(adminLocal);
                    ctx.ejecutar("rm -f " + remotaAdmin, 30);
                }
                long trasAdmin = aEntero(ctx.ejecutar(
                        q + db + " -N -B -e \"SELECT COUNT(*) FROM user\"", 60).primeraLinea());
                if (trasAdmin < 1) {
                    throw new StageException(StageException.Tipo.FATAL,
                            "El INSERT del administrador no dejó registros (verifique el correo duplicado).");
                }
                ctx.ok("Administrador inicial creado: " + ctx.adminEmail + " (rol ADMINISTRADOR)");
            } else {
                ctx.log("  Ya hay " + usuarios + " usuario(s); no se crea administrador.");
            }

            // ------------------------------------------- Nombre de la institución
            String nombreInst = ctx.institucion.getNombre();
            String cfgSql = "UPDATE `system_configuration` SET `value`='" + CommandValidator.escaparSql(nombreInst)
                    + "', `updated_at`=NOW() WHERE `key`='application_name';";
            Path cfgLocal = escribirTemp("bolsa-cfg-", ".sql", cfgSql);
            String remotaCfg = "/tmp/bolsa-" + ctx.institucion.getSlug() + "-cfg.sql";
            try {
                ctx.ssh.subir(cfgLocal, remotaCfg);
                ctx.exigirExito(q + db + " < " + remotaCfg, 60,
                        "Actualización de system_configuration");
            } finally {
                Files.deleteIfExists(cfgLocal);
                ctx.ejecutar("rm -f " + remotaCfg, 30);
            }
            String nombreLeido = ctx.ejecutar(
                    q + db + " -N -B -e \"SELECT value FROM system_configuration WHERE `key`='application_name'\"",
                    60).primeraLinea().trim();
            if (!nombreLeido.equals(nombreInst)) {
                throw new StageException(StageException.Tipo.FATAL,
                        "El nombre de la institución no quedó guardado (leído: '" + nombreLeido + "').");
            }
            ctx.ok("system_configuration.application_name = \"" + nombreInst + "\"");

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
