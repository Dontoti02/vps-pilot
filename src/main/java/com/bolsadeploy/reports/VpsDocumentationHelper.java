package com.bolsadeploy.reports;

import com.bolsadeploy.deployment.DeploymentContext;
import com.bolsadeploy.deployment.StageException;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.security.PasswordHasher;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Utilidades de consulta y sincronización en vivo con el VPS para completar
 * automáticamente la documentación PDF de entrega (enlace HTTPS real, datos
 * de {@code .env}, correo del administrador y generación/actualización segura
 * de la contraseña del administrador directamente en la base de datos del VPS).
 */
public final class VpsDocumentationHelper {

    /** Resultado de leer el estado real de producción desde el VPS. */
    public record DatosVpsDetectados(
            Map<String, String> env,
            boolean httpsActivo,
            String adminEmail,
            String adminTabla,
            String detalle
    ) {
    }

    /** Resultado de aplicar/generar credenciales de administrador en el VPS. */
    public record ResultadoAdminVps(
            boolean exito,
            String emailAplicado,
            String tablaAfectada,
            String accion,
            String mensaje
    ) {
    }

    private static final String ALFABETO_CLARO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private VpsDocumentationHelper() {
    }

    /**
     * Genera una contraseña segura, fácil de leer y compatible con Laravel
     * (mayúsculas, minúsculas, números y símbolo; sin caracteres ambiguos 0/O/1/l/I).
     */
    public static String generarPasswordAdminAmigable(String slug) {
        String prefijo = "Admin";
        if (slug != null && !slug.isBlank()) {
            String limpio = slug.replaceAll("[^A-Za-z]", "");
            if (limpio.length() >= 3) {
                String p = limpio.substring(0, Math.min(6, limpio.length()));
                prefijo = Character.toUpperCase(p.charAt(0)) + p.substring(1).toLowerCase();
            }
        }
        SecureRandom rnd = new SecureRandom();
        StringBuilder sufijo = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sufijo.append(ALFABETO_CLARO.charAt(rnd.nextInt(ALFABETO_CLARO.length())));
        }
        int num = 10 + rnd.nextInt(89);
        return prefijo + "#" + num + sufijo + "!";
    }

    /**
     * Lee desde el VPS el archivo {@code .env}, el estado real de HTTPS/SSL en Nginx
     * y el correo del usuario administrador activo directamente desde la base de datos.
     */
    public static DatosVpsDetectados leerDatosRealesDesdeVps(DeploymentContext ctx) throws StageException {
        String dominio = ctx.institucion.dominioPrincipal();
        String shared = ctx.rutaShared();
        String current = ctx.rutaReleaseActual();

        // 1. Leer claves seguras de .env
        CommandResult envRes = ctx.ejecutar(
                "FILE=\"" + shared + "/.env\"; [ -s \"$FILE\" ] || FILE=\"" + current + "/.env\"; "
                        + "if [ -s \"$FILE\" ]; then "
                        + "grep -E '^(DB_CONNECTION|DB_HOST|DB_PORT|DB_DATABASE|DB_USERNAME|DB_PASSWORD|APP_URL)=' \"$FILE\" 2>/dev/null; "
                        + "fi", 25);
        Map<String, String> envMap = parsearEnv(envRes.stdout());

        // 2. Detectar HTTPS real (APP_URL, certificado Let's Encrypt o Nginx 443)
        boolean https = envMap.getOrDefault("APP_URL", "").toLowerCase().startsWith("https://");
        if (!https && dominio != null && !dominio.isBlank()) {
            CommandResult sslRes = ctx.ejecutar(
                    "if [ -d \"/etc/letsencrypt/live/" + dominio + "\" ] || "
                            + ctx.sudo("grep -Rql 'ssl_certificate' /etc/nginx/sites-enabled/ 2>/dev/null && grep -Rql '" + dominio + "' /etc/nginx/sites-enabled/ 2>/dev/null")
                            + "; then echo HTTPS; fi", 20);
            if ("HTTPS".equals(sslRes.primeraLinea().trim())) {
                https = true;
            }
        }

        // 3. Detectar correo real del administrador desde la BD de Laravel (solo lectura)
        String adminEmail = "";
        String adminTabla = "";
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + current + "/artisan && test -f " + current + "/vendor/autoload.php && echo SI", 15);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            String php = buildReadAdminPhpScript();
            String b64 = Base64.getEncoder().encodeToString(php.getBytes(StandardCharsets.UTF_8));
            String tmpPhp = "/tmp/vpspilot-read-admin-" + ctx.institucion.getSlug() + ".php";
            CommandResult admRes = ctx.ejecutar(
                    "echo " + b64 + " | base64 -d > " + tmpPhp
                            + " && chmod 644 " + tmpPhp
                            + " && cd " + current + " && sudo -u www-data php " + tmpPhp
                            + " ; rm -f " + tmpPhp, 30);
            for (String linea : admRes.salida().split("\\R+")) {
                String t = linea.trim();
                if (t.startsWith("ADMIN_EMAIL=")) {
                    adminEmail = t.substring("ADMIN_EMAIL=".length()).trim();
                } else if (t.startsWith("ADMIN_TABLE=")) {
                    adminTabla = t.substring("ADMIN_TABLE=".length()).trim();
                }
            }
        }

        // Fallback con sudo mysql si el proyecto no tiene Laravel o falló PDO
        String dbName = envMap.getOrDefault("DB_DATABASE",
                ctx.institucion.getNombreBaseDatos() == null ? "" : ctx.institucion.getNombreBaseDatos());
        if (adminEmail.isBlank() && !dbName.isBlank() && dbName.matches("[A-Za-z0-9_]+")) {
            CommandResult sqlRes = ctx.ejecutar(
                    ctx.sudo("mysql -N -B \"" + dbName + "\" -e \""
                            + "SELECT email FROM user WHERE rol_id=1 ORDER BY is_active DESC, id ASC LIMIT 1;\" 2>/dev/null")
                            + " || "
                            + ctx.sudo("mysql -N -B \"" + dbName + "\" -e \""
                            + "SELECT email FROM users ORDER BY id ASC LIMIT 1;\" 2>/dev/null")
                            + " || true", 20);
            String cand = sqlRes.primeraLinea().trim();
            if (cand.contains("@") && !cand.contains(" ")) {
                adminEmail = cand;
                adminTabla = "user/users";
            }
        }

        StringBuilder det = new StringBuilder();
        if (!envMap.isEmpty()) det.append(".env cargado");
        if (https) det.append(det.isEmpty() ? "" : " | ").append("HTTPS activo");
        if (!adminEmail.isBlank()) det.append(det.isEmpty() ? "" : " | ").append("Admin: ").append(adminEmail);

        return new DatosVpsDetectados(envMap, https, adminEmail, adminTabla, det.toString());
    }

    /**
     * Aplica (actualiza o crea si no existe) el usuario administrador y su nueva
     * contraseña directamente en la base de datos del VPS usando el hasher nativo
     * de Laravel ({@code Hash::make}) y verifica con {@code Hash::check} que el
     * inicio de sesión funcionará inmediatamente.
     */
    public static ResultadoAdminVps aplicarCredencialesAdminEnVps(DeploymentContext ctx,
                                                                  String email,
                                                                  String passwordPlana,
                                                                  String nombreAdmin) throws StageException {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return new ResultadoAdminVps(false, "", "", "", "Ingresa un correo de administrador válido.");
        }
        if (passwordPlana == null || passwordPlana.length() < 6) {
            return new ResultadoAdminVps(false, email, "", "", "La contraseña debe tener al menos 6 caracteres.");
        }

        String current = ctx.rutaReleaseActual();
        String nombreLimpio = (nombreAdmin == null || nombreAdmin.isBlank()) ? "Administrador" : nombreAdmin.trim();

        CommandResult tieneArtisan = ctx.ejecutar(
                "test -f " + current + "/artisan && test -f " + current + "/vendor/autoload.php && echo SI", 15);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            String php = buildSetAdminPasswordPhpScript(email.trim(), passwordPlana, nombreLimpio);
            String b64 = Base64.getEncoder().encodeToString(php.getBytes(StandardCharsets.UTF_8));
            String tmpPhp = "/tmp/vpspilot-set-admin-" + ctx.institucion.getSlug() + ".php";

            CommandResult res = ctx.ejecutar(
                    "echo " + b64 + " | base64 -d > " + tmpPhp
                            + " && " + ctx.sudo("chown www-data:www-data " + tmpPhp)
                            + " && " + ctx.sudo("chmod 600 " + tmpPhp)
                            + " && cd " + current + " && sudo -u www-data php " + tmpPhp
                            + " ; EC=$?; " + ctx.sudo("rm -f " + tmpPhp) + "; exit $EC", 45);

            String out = res.salida() == null ? "" : res.salida().trim();
            for (String linea : out.split("\\R+")) {
                String t = linea.trim();
                if (t.startsWith("ADMIN_OK|")) {
                    String[] p = t.split("\\|", -1);
                    String accion = p.length > 1 ? p[1] : "UPDATED";
                    String tabla = p.length > 2 ? p[2] : "user";
                    String mail = p.length > 3 ? p[3] : email.trim();
                    String msg = "CREATED".equals(accion)
                            ? "Administrador creado en el VPS (" + tabla + "): " + mail
                            : "Contraseña actualizada y verificada en el VPS (" + tabla + ") para: " + mail;
                    return new ResultadoAdminVps(true, mail, tabla, accion, msg);
                }
            }
            return new ResultadoAdminVps(false, email.trim(), "", "",
                    "No se pudo actualizar el administrador en el VPS: " + recortar(out));
        }

        // Fallback MySQL directo si no es Laravel pero tiene tabla `user`
        String dbName = ctx.institucion.getNombreBaseDatos();
        if (dbName != null && !dbName.isBlank() && dbName.matches("[A-Za-z0-9_]+")) {
            String bcrypt = PasswordHasher.hash(passwordPlana);
            String b64Sql = Base64.getEncoder().encodeToString(
                    ("UPDATE `user` SET `password`='" + bcrypt.replace("'", "''")
                            + "', `is_active`=1, `attempts`=0, `updated_at`=NOW() WHERE `email`='"
                            + email.trim().replace("'", "''") + "';\n").getBytes(StandardCharsets.UTF_8));
            String tmpSql = "/tmp/vpspilot-set-admin-" + ctx.institucion.getSlug() + ".sql";
            CommandResult r = ctx.ejecutar(
                    "echo " + b64Sql + " | base64 -d > " + tmpSql
                            + " && " + ctx.sudo("chmod 600 " + tmpSql)
                            + " && " + ctx.sudo("mysql \"" + dbName + "\" < " + tmpSql)
                            + " ; EC=$?; " + ctx.sudo("rm -f " + tmpSql) + "; exit $EC", 30);
            if (r.exito()) {
                return new ResultadoAdminVps(true, email.trim(), "user", "UPDATED",
                        "Contraseña actualizada por MySQL en '" + dbName + "' para: " + email.trim());
            }
            return new ResultadoAdminVps(false, email.trim(), "user", "",
                    "Error actualizando contraseña en MySQL: " + recortar(r.salida()));
        }

        return new ResultadoAdminVps(false, email.trim(), "", "",
                "No se detectó aplicación Laravel ni base de datos MySQL accesible en " + current);
    }

    static Map<String, String> parsearEnv(String texto) {
        Map<String, String> mapa = new HashMap<>();
        if (texto == null || texto.isBlank()) {
            return mapa;
        }
        for (String linea : texto.split("\\R+")) {
            int eq = linea.indexOf('=');
            if (eq <= 0) continue;
            String k = linea.substring(0, eq).trim();
            String v = linea.substring(eq + 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            mapa.put(k, v);
        }
        return mapa;
    }

    static String buildReadAdminPhpScript() {
        return """
                <?php
                require 'vendor/autoload.php';
                $app = require 'bootstrap/app.php';
                $app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap();

                use Illuminate\\Support\\Facades\\DB;
                use Illuminate\\Support\\Facades\\Schema;

                try {
                    if (Schema::hasTable('user')) {
                        $row = null;
                        if (Schema::hasColumn('user', 'rol_id')) {
                            $q = DB::table('user')->where('rol_id', 1);
                            if (Schema::hasColumn('user', 'is_active')) {
                                $q->orderByDesc('is_active');
                            }
                            $row = $q->orderBy('id')->first();
                        }
                        if (!$row) {
                            $row = DB::table('user')->orderBy('id')->first();
                        }
                        if ($row && !empty($row->email)) {
                            echo "ADMIN_TABLE=user\\n";
                            echo "ADMIN_EMAIL=" . trim((string) $row->email) . "\\n";
                            exit(0);
                        }
                    }
                    if (Schema::hasTable('users')) {
                        $row = DB::table('users')->orderBy('id')->first();
                        if ($row && !empty($row->email)) {
                            echo "ADMIN_TABLE=users\\n";
                            echo "ADMIN_EMAIL=" . trim((string) $row->email) . "\\n";
                            exit(0);
                        }
                    }
                    echo "ADMIN_NONE\\n";
                } catch (Throwable $e) {
                    echo "ADMIN_ERR=" . $e->getMessage() . "\\n";
                    exit(1);
                }
                """;
    }

    static String buildSetAdminPasswordPhpScript(String email, String plainPassword, String nombreAdmin) {
        String emailB64 = Base64.getEncoder().encodeToString(email.getBytes(StandardCharsets.UTF_8));
        String passB64 = Base64.getEncoder().encodeToString(plainPassword.getBytes(StandardCharsets.UTF_8));
        String nameB64 = Base64.getEncoder().encodeToString(nombreAdmin.getBytes(StandardCharsets.UTF_8));
        return """
                <?php
                require 'vendor/autoload.php';
                $app = require 'bootstrap/app.php';
                $app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap();

                use Illuminate\\Support\\Facades\\DB;
                use Illuminate\\Support\\Facades\\Hash;
                use Illuminate\\Support\\Facades\\Schema;

                $email = base64_decode('%s');
                $plain = base64_decode('%s');
                $name  = base64_decode('%s');
                $hash  = Hash::make($plain);

                if (!Hash::check($plain, $hash)) {
                    echo "ADMIN_ERR|No se pudo verificar el hash generado\\n";
                    exit(1);
                }

                try {
                    if (Schema::hasTable('user')) {
                        $existing = DB::table('user')->where('email', $email)->first();
                        if (!$existing && Schema::hasColumn('user', 'rol_id')) {
                            $existing = DB::table('user')->where('rol_id', 1)->orderBy('id')->first();
                        }
                        if ($existing) {
                            $upd = ['password' => $hash, 'email' => $email];
                            if (Schema::hasColumn('user', 'is_active')) $upd['is_active'] = 1;
                            if (Schema::hasColumn('user', 'attempts')) $upd['attempts'] = 0;
                            if (Schema::hasColumn('user', 'updated_at')) $upd['updated_at'] = now();
                            DB::table('user')->where('id', $existing->id)->update($upd);
                            if (!empty($existing->person_id) && Schema::hasTable('person') && Schema::hasColumn('person', 'email')) {
                                DB::table('person')->where('id', $existing->person_id)->update(['email' => $email]);
                            }
                            $check = DB::table('user')->where('id', $existing->id)->value('password');
                            if (!Hash::check($plain, (string) $check)) {
                                echo "ADMIN_ERR|El hash almacenado no coincide tras actualizar\\n";
                                exit(1);
                            }
                            echo "ADMIN_OK|UPDATED|user|" . $email . "\\n";
                            exit(0);
                        }

                        // Crear administrador inicial si la tabla user no tenía ninguno
                        $personId = null;
                        if (Schema::hasTable('person')) {
                            $personData = [
                                'document_type' => 'DNI',
                                'document_number' => '00000000',
                                'names' => $name,
                                'phone' => '00000000',
                                'email' => $email,
                            ];
                            if (Schema::hasColumn('person', 'created_at')) $personData['created_at'] = now();
                            if (Schema::hasColumn('person', 'updated_at')) $personData['updated_at'] = now();
                            $personId = DB::table('person')->insertGetId($personData);
                        }
                        $ins = [
                            'email' => $email,
                            'password' => $hash,
                        ];
                        if (Schema::hasColumn('user', 'company_id')) $ins['company_id'] = null;
                        if (Schema::hasColumn('user', 'person_id')) $ins['person_id'] = $personId;
                        if (Schema::hasColumn('user', 'rol_id')) $ins['rol_id'] = 1;
                        if (Schema::hasColumn('user', 'is_active')) $ins['is_active'] = 1;
                        if (Schema::hasColumn('user', 'attempts')) $ins['attempts'] = 0;
                        if (Schema::hasColumn('user', 'created_at')) $ins['created_at'] = now();
                        if (Schema::hasColumn('user', 'updated_at')) $ins['updated_at'] = now();
                        $uid = DB::table('user')->insertGetId($ins);
                        if (Schema::hasTable('rol_user')) {
                            DB::table('rol_user')->insert(['rol_id' => 1, 'user_id' => $uid]);
                        }
                        echo "ADMIN_OK|CREATED|user|" . $email . "\\n";
                        exit(0);
                    }

                    if (Schema::hasTable('users')) {
                        $existing = DB::table('users')->where('email', $email)->first();
                        if (!$existing) {
                            $existing = DB::table('users')->orderBy('id')->first();
                        }
                        if ($existing) {
                            $upd = ['email' => $email, 'password' => $hash];
                            if (Schema::hasColumn('users', 'updated_at')) $upd['updated_at'] = now();
                            DB::table('users')->where('id', $existing->id)->update($upd);
                            echo "ADMIN_OK|UPDATED|users|" . $email . "\\n";
                            exit(0);
                        }
                        $ins = ['name' => $name, 'email' => $email, 'password' => $hash];
                        if (Schema::hasColumn('users', 'created_at')) $ins['created_at'] = now();
                        if (Schema::hasColumn('users', 'updated_at')) $ins['updated_at'] = now();
                        DB::table('users')->insert($ins);
                        echo "ADMIN_OK|CREATED|users|" . $email . "\\n";
                        exit(0);
                    }

                    echo "ADMIN_ERR|No se encontró tabla 'user' ni 'users' en la base de datos\\n";
                    exit(1);
                } catch (Throwable $e) {
                    echo "ADMIN_ERR|" . $e->getMessage() . "\\n";
                    exit(1);
                }
                """.formatted(emailB64, passB64, nameB64);
    }

    private static String recortar(String s) {
        if (s == null || s.isBlank()) return "(sin salida)";
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 350 ? l.substring(0, 350) + "…" : l;
    }
}
