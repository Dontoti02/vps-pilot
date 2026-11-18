package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Etapa 8 — Migraciones, caché de Laravel y permisos.
 *
 * <ul>
 *   <li>Prepara permisos de escritura ({@code storage}, {@code bootstrap/cache},
 *       {@code uploads}, {@code .env}) ANTES de invocar Artisan.</li>
 *   <li>Verifica conectividad real de base de datos en Laravel antes de migrar.</li>
 *   <li>{@code php artisan migrate --force}: aplica migraciones pendientes y
 *       reconcilia de forma segura tablas/columnas ya existentes en volcados
 *       previos (solo si el 100% de sus tablas/columnas ya existen en BD).</li>
 *   <li>{@code storage:link}, caché segura (evita {@code config:cache} si la
 *       app invoca {@code env()} directamente fuera de {@code config/}).</li>
 *   <li>Permisos seguros sin 777 usando elevación {@code sudo} cuando aplica.</li>
 * </ul>
 *
 * Nunca se ejecuta {@code migrate:fresh} ni {@code db:wipe}.
 */
public class MigracionStage implements DeploymentStage {

    @Override
    public String id() {
        return "migracion";
    }

    @Override
    public String nombre() {
        return "Migraciones, caché y permisos";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        return false; // siempre se ejecuta: es idempotente y garantiza permisos
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String release = ctx.valores.get("releasePath");
        String shared = ctx.rutaShared();
        if (release == null || release.isBlank()) {
            throw new StageException(StageException.Tipo.FATAL, "No se conoce la ruta de la release.");
        }

        // -------------------------------------- Permisos previos a Artisan
        asegurarPermisosEscritura(ctx, release, shared);

        // -------------------------------------- Comandos Artisan (si aplica)
        CommandResult tieneArtisan = ctx.ejecutar("test -f " + release + "/artisan && echo SI", 30);
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            // Limpiar caché de config previa para leer siempre el shared/.env vigente
            ctx.ejecutar("cd " + release + " && sudo -u www-data php artisan config:clear >/dev/null 2>&1 || true", 60);

            // Verificar conectividad real de BD antes de migrar
            verificarConexionBdLaravel(ctx, release, shared);

            // Migraciones (con reconciliación segura si un volcado previo ya creó tablas/columnas)
            ejecutarMigracionesLaravel(ctx, release);

            // storage:link
            CommandResult tieneStorage = ctx.ejecutar("test -d " + release + "/storage && echo SI", 30);
            if ("SI".equals(tieneStorage.primeraLinea().trim())) {
                ctx.ejecutar("mkdir -p " + shared + "/storage/app/public", 15);
                CommandResult link = ctx.ejecutar(
                        "cd " + release + " && sudo -u www-data php artisan storage:link --force", 120);
                if (link.exito()) {
                    ctx.ok("Enlace public/storage asegurado");
                }
            }

            // Cachés seguras de Laravel
            optimizarCachesLaravel(ctx, release);
        } else {
            ctx.log("  [i] Repositorio sin artisan; se omiten migraciones y cachés de Laravel.");
        }

        // ----------------------------------------------------------- Permisos finales
        ctx.log("  Aplicando permisos seguros…");
        ctx.exigirExito(ctx.sudo("chmod -R u=rwX,g=rX,o=rX " + release), 600, "Permisos base de la release");
        asegurarPermisosEscritura(ctx, release, shared);

        // Eliminar cualquier 777 residual y verificar que no quede ninguno
        ctx.ejecutar(ctx.sudo("find " + release + " " + shared + " -perm 0777 -not -type l "
                + "-exec chmod 755 {} + 2>/dev/null || true"), 300);
        CommandResult setenta = ctx.ejecutar(
                "find " + release + " " + shared + " -perm 0777 -not -type l 2>/dev/null | head -5", 60);
        if (!setenta.salida().isBlank()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Quedaron archivos/directorios con permiso 777: " + limpiar(setenta.salida()));
        }
        ctx.ok("Permisos verificados: sin 777");

        // ------------------------------------------------------ Verificación final
        if ("SI".equals(tieneArtisan.primeraLinea().trim())) {
            CommandResult status = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan migrate:status", 300);
            if (!status.exito()) {
                ctx.log("  [!] Aviso: php artisan migrate:status: " + limpiar(status.salida()));
            } else {
                ctx.ok("migrate:status correcto (esquema consistente)");
            }
        }
    }

    /**
     * Limpia y regenera las cachés de Laravel de forma segura en producción:
     * <ul>
     *   <li>Si detecta llamadas directas a {@code env()} en {@code app/}, {@code routes/}
     *       o {@code resources/}, omite {@code config:cache} para evitar que {@code env()}
     *       devuelva {@code null} en tiempo de ejecución.</li>
     *   <li>Si {@code route:cache} falla (por ejemplo rutas con closures), limpia con
     *       {@code route:clear} sin abortar el resto de cachés.</li>
     * </ul>
     */
    public static void optimizarCachesLaravel(DeploymentContext ctx, String dir) throws StageException {
        ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan view:clear >/dev/null 2>&1 || true", 60);
        ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan config:clear >/dev/null 2>&1 || true", 60);
        ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan route:clear >/dev/null 2>&1 || true", 60);

        // Comprobar si la app usa env() directamente fuera de config/
        CommandResult usaEnvDirecto = ctx.ejecutar(
                "grep -rnE '(^|[^A-Za-z0-9_])env\\s*\\(' " + dir + "/app " + dir + "/routes " + dir + "/resources 2>/dev/null | head -1",
                30);
        if (!usaEnvDirecto.primeraLinea().trim().isBlank()) {
            ctx.log("  [i] Uso directo de env() detectado fuera de config/; se mantiene config sin cachear (config:clear).");
        } else {
            CommandResult cfgCache = ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan config:cache", 300);
            if (!cfgCache.exito()) {
                ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan config:clear >/dev/null 2>&1 || true", 60);
                ctx.log("  [!] Aviso: config:cache falló; se dejó config:clear: " + limpiar(cfgCache.salida()));
            }
        }

        CommandResult rutas = ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan route:cache", 300);
        if (rutas.exito()) {
            ctx.ok("Rutas cacheadas");
        } else {
            ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan route:clear >/dev/null 2>&1 || true", 60);
        }

        CommandResult vistas = ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan view:cache", 300);
        if (vistas.exito()) {
            ctx.ok("Vistas compiladas");
        } else {
            ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan view:clear >/dev/null 2>&1 || true", 60);
            ctx.log("  [!] Aviso: view:cache terminó con observaciones: " + limpiar(vistas.salida()));
        }

        ctx.ejecutar("cd " + dir + " && sudo -u www-data php artisan queue:restart >/dev/null 2>&1 || true", 60);
    }

    // ------------------------------------------------------------------
    // Helpers de permisos, BD y migraciones
    // ------------------------------------------------------------------

    private void asegurarPermisosEscritura(DeploymentContext ctx, String release, String shared) throws StageException {
        ctx.ejecutar("if [ -f " + release + "/artisan ] || [ -d " + release + "/storage ]; then "
                + "mkdir -p " + shared + "/storage/logs " + shared + "/storage/framework/cache/data "
                + shared + "/storage/framework/sessions " + shared + "/storage/framework/views "
                + shared + "/storage/app/public; fi", 30);
        ctx.ejecutar("test -d " + release + "/bootstrap && mkdir -p " + release + "/bootstrap/cache || true", 30);

        aplicarPermisosDirectorio(ctx, release + "/bootstrap/cache");
        aplicarPermisosDirectorio(ctx, shared + "/storage");
        aplicarPermisosDirectorio(ctx, shared + "/uploads");
        aplicarPermisosDirectorio(ctx, shared + "/public/uploads");

        ctx.ejecutar("if [ -f " + shared + "/.env ]; then "
                + ctx.sudo("chown www-data:www-data " + shared + "/.env") + " && "
                + ctx.sudo("chmod 640 " + shared + "/.env") + "; fi", 60);
        ctx.ejecutar("if [ -f " + shared + "/database.sqlite ]; then "
                + ctx.sudo("chown www-data:www-data " + shared + "/database.sqlite") + " && "
                + ctx.sudo("chmod 664 " + shared + "/database.sqlite") + "; fi", 30);
    }

    private void aplicarPermisosDirectorio(DeploymentContext ctx, String dir) throws StageException {
        ctx.ejecutar("if [ -d " + dir + " ]; then "
                + ctx.sudo("chown -R www-data:www-data " + dir) + " && "
                + ctx.sudo("chmod -R 775 " + dir) + "; fi", 120);
    }

    /** Verifica conectividad real de Laravel con la base de datos configurada en shared/.env. */
    void verificarConexionBdLaravel(DeploymentContext ctx, String release, String shared) throws StageException {
        String motorInst = ctx.institucion.getTipoBaseDatos() != null
                ? ctx.institucion.getTipoBaseDatos().trim().toUpperCase() : "MYSQL";
        if ("NINGUNA".equals(motorInst)) {
            CommandResult usaBdEnEnv = ctx.ejecutar(
                    "grep -qE '^(SESSION_DRIVER|CACHE_STORE)=database' " + shared + "/.env 2>/dev/null && echo SI", 15);
            if (!"SI".equals(usaBdEnEnv.primeraLinea().trim())) {
                return;
            }
        }

        String phpCheck = buildDbCheckScript();
        String b64 = Base64.getEncoder().encodeToString(phpCheck.getBytes(StandardCharsets.UTF_8));
        String tmpPhp = "/tmp/vpspilot-dbcheck-" + ctx.institucion.getSlug() + ".php";

        CommandResult res = ctx.ejecutar(
                "echo " + b64 + " | base64 -d > " + tmpPhp
                        + " && chmod 644 " + tmpPhp
                        + " && cd " + release + " && sudo -u www-data php " + tmpPhp
                        + " ; EC=$?; rm -f " + tmpPhp + "; exit $EC", 60);

        if (res.exito() && (res.salida().contains("DB_OK:") || res.salida().contains("DB_NONE"))) {
            ctx.ok("Conexión a base de datos verificada desde Laravel (" + res.primeraLinea().trim() + ")");
            return;
        }

        // Leer resumen seguro de shared/.env (sin imprimir nunca DB_PASSWORD)
        CommandResult resumenEnv = ctx.ejecutar(
                "awk -F= '/^(DB_CONNECTION|DB_HOST|DB_PORT|DB_DATABASE|DB_USERNAME)=/{printf \"%s=%s \", $1, $2} "
                        + "/^DB_PASSWORD=/{printf \"DB_PASSWORD=%s \", (length($2)>0?\"(definida)\":\"(VACÍA)\")}' "
                        + shared + "/.env 2>/dev/null", 15);
        String envInfo = resumenEnv.primeraLinea().trim();
        throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                "Laravel no puede conectarse a la base de datos (esto causaría 500 Server Error en producción): "
                        + limpiar(res.salida())
                        + (envInfo.isBlank() ? "" : "\nConfiguración actual en shared/.env: " + envInfo));
    }

    /** Ejecuta php artisan migrate --force con reconciliación segura de tablas/columnas preexistentes. */
    void ejecutarMigracionesLaravel(DeploymentContext ctx, String release) throws StageException {
        String motorInst = ctx.institucion.getTipoBaseDatos() != null
                ? ctx.institucion.getTipoBaseDatos().trim().toUpperCase() : "MYSQL";
        if ("NINGUNA".equals(motorInst)) {
            CommandResult tieneMigs = ctx.ejecutar("ls " + release + "/database/migrations/*.php >/dev/null 2>&1 && echo SI", 15);
            if (!"SI".equals(tieneMigs.primeraLinea().trim())) {
                return;
            }
        }

        ctx.log("  php artisan migrate --force…");
        int maxReconciliaciones = 20;
        for (int intento = 0; intento <= maxReconciliaciones; intento++) {
            CommandResult mig = ctx.ejecutar(
                    "cd " + release + " && sudo -u www-data php artisan migrate --force --no-interaction", 600);
            if (mig.exito()) {
                String migSalida = mig.salida().replaceAll("\\s+", " ").trim();
                ctx.ok("Migraciones: " + recortar(migSalida, 300));
                return;
            }

            String err = mig.salida() == null ? "" : mig.salida();
            boolean esPreexistente = err.contains("42S01") || err.contains("1050")
                    || err.contains("42P07") || err.contains("already exists")
                    || err.contains("42S21") || err.contains("1060") || err.contains("Duplicate column");

            if (esPreexistente && intento < maxReconciliaciones) {
                String reconciliado = intentarReconciliarMigracionPreexistente(ctx, release);
                if (reconciliado != null) {
                    ctx.log("  [i] Migración ya aplicada en BD registrada en 'migrations': " + reconciliado);
                    continue;
                }
            }

            throw new StageException(StageException.Tipo.REQUIERE_APROBACION,
                    "php artisan migrate --force falló (no se activa la release para evitar 500 Server Error): "
                            + limpiar(err));
        }
    }

    /**
     * Si la primera migración pendiente únicamente crea tablas o añade columnas que
     * ya existen al 100% en la base de datos (p. ej. tras importar un volcado SQL),
     * la registra en la tabla {@code migrations} sin tocar ningún dato ni estructura.
     */
    private String intentarReconciliarMigracionPreexistente(DeploymentContext ctx, String release) throws StageException {
        String phpScript = buildReconcileMigrationScript();
        String b64 = Base64.getEncoder().encodeToString(phpScript.getBytes(StandardCharsets.UTF_8));
        String tmpPhp = "/tmp/vpspilot-migrec-" + ctx.institucion.getSlug() + ".php";

        CommandResult res = ctx.ejecutar(
                "echo " + b64 + " | base64 -d > " + tmpPhp
                        + " && chmod 644 " + tmpPhp
                        + " && cd " + release + " && sudo -u www-data php " + tmpPhp
                        + " ; EC=$?; rm -f " + tmpPhp + "; exit $EC", 60);

        if (res.exito()) {
            for (String linea : res.salida().split("\\R+")) {
                String t = linea.trim();
                if (t.startsWith("RECONCILED:")) {
                    return t.substring("RECONCILED:".length());
                }
            }
        }
        return null;
    }

    static String buildDbCheckScript() {
        return """
                <?php
                require 'vendor/autoload.php';
                $app = require 'bootstrap/app.php';
                $app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap();
                $conn = config('database.default');
                if (!$conn) {
                    echo "DB_NONE\\n";
                    exit(0);
                }
                try {
                    Illuminate\\Support\\Facades\\DB::connection()->getPdo();
                    echo "DB_OK:" . $conn . "\\n";
                } catch (Throwable $e) {
                    echo "DB_ERR:" . $conn . ":" . $e->getMessage() . "\\n";
                    exit(1);
                }
                """;
    }

    static String buildReconcileMigrationScript() {
        return """
                <?php
                require 'vendor/autoload.php';
                $app = require 'bootstrap/app.php';
                $app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap();

                use Illuminate\\Support\\Facades\\DB;
                use Illuminate\\Support\\Facades\\Schema;

                $migTable = config('database.migrations.table', config('database.migrations', 'migrations'));
                if (!is_string($migTable) || $migTable === '') {
                    $migTable = 'migrations';
                }
                if (!Schema::hasTable($migTable)) {
                    echo "NO_MIG_TABLE\\n";
                    exit(2);
                }

                $ran = DB::table($migTable)->pluck('migration')->all();
                $files = glob(database_path('migrations/*.php')) ?: [];
                sort($files);
                $pendingFile = null;
                $pendingName = null;
                foreach ($files as $f) {
                    $name = basename($f, '.php');
                    if (!in_array($name, $ran, true)) {
                        $pendingFile = $f;
                        $pendingName = $name;
                        break;
                    }
                }
                if (!$pendingFile) {
                    echo "NO_PENDING\\n";
                    exit(2);
                }

                $src = file_get_contents($pendingFile);
                if (preg_match('/function\\s+up\\s*\\([^)]*\\)[^{]*\\{(.*?)(?:function\\s+down\\s*\\(|\\z)/s', $src, $m)) {
                    $upBody = $m[1];
                } else {
                    $upBody = $src;
                }
                $upClean = preg_replace('#/\\*.*?\\*/#s', '', $upBody);
                $upClean = preg_replace('#//[^\\n]*#', '', $upClean);

                $canReconcile = false;
                $reason = '';

                // Caso 1: migración solo de Schema::create donde todas las tablas ya existen
                preg_match_all('/Schema::create\\s*\\(\\s*[\\'"]([A-Za-z0-9_]+)[\\'"]/', $upClean, $cm);
                $createdTables = array_unique($cm[1] ?? []);
                $hasAlter = (bool) preg_match('/Schema::(?:table|rename|drop|dropIfExists)\\s*\\(/', $upClean);
                $hasDbWrite = (bool) preg_match('/DB::(?:table|statement|insert|update|delete)\\s*\\(/', $upClean);

                if (!empty($createdTables) && !$hasAlter && !$hasDbWrite) {
                    $allExist = true;
                    foreach ($createdTables as $t) {
                        if (!Schema::hasTable($t)) {
                            $allExist = false;
                            break;
                        }
                    }
                    if ($allExist) {
                        $canReconcile = true;
                        $reason = 'tablas ya existentes: ' . implode(', ', $createdTables);
                    }
                }

                // Caso 2: migración de Schema::table que solo añade columnas y todas ya existen
                if (!$canReconcile && empty($createdTables) && !$hasDbWrite
                    && !preg_match('/Schema::(?:rename|drop|dropIfExists)\\s*\\(|renameColumn|dropColumn/', $upClean)) {
                    preg_match_all('/Schema::table\\s*\\(\\s*[\\'"]([A-Za-z0-9_]+)[\\'"]/', $upClean, $tm);
                    $alteredTables = array_unique($tm[1] ?? []);
                    if (count($alteredTables) === 1 && Schema::hasTable($alteredTables[0])) {
                        $tbl = $alteredTables[0];
                        preg_match_all('/\\$table->([A-Za-z_]+)\\s*\\(\\s*[\\'"]([A-Za-z0-9_]+)[\\'"]/', $upClean, $colm, PREG_SET_ORDER);
                        $skip = ['foreign','index','unique','primary','fullText','spatialIndex','dropForeign','dropIndex','dropUnique','dropPrimary','dropColumn','renameColumn'];
                        $cols = [];
                        foreach ($colm as $pair) {
                            if (!in_array($pair[1], $skip, true)) {
                                $cols[] = $pair[2];
                            }
                        }
                        $cols = array_unique($cols);
                        if (!empty($cols)) {
                            $allCols = true;
                            foreach ($cols as $c) {
                                if (!Schema::hasColumn($tbl, $c)) {
                                    $allCols = false;
                                    break;
                                }
                            }
                            if ($allCols) {
                                $canReconcile = true;
                                $reason = "columnas ya existentes en {$tbl}: " . implode(', ', $cols);
                            }
                        }
                    }
                }

                if ($canReconcile) {
                    $batch = (int) (DB::table($migTable)->max('batch') ?? 0) + 1;
                    DB::table($migTable)->insert(['migration' => $pendingName, 'batch' => $batch]);
                    echo "RECONCILED:{$pendingName} ({$reason})\\n";
                    exit(0);
                }

                echo "CANNOT_RECONCILE:{$pendingName}\\n";
                exit(1);
                """;
    }

    private static String recortar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String limpiar(String s) {
        if (s == null || s.isBlank()) {
            return "(sin salida)";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 700 ? l.substring(0, 700) + "…" : l;
    }
}
