package com.bolsadeploy.database;

import com.bolsadeploy.application.AppContext;
import com.bolsadeploy.domain.Institution;
import com.bolsadeploy.domain.VpsServer;
import com.bolsadeploy.infrastructure.ssh.CommandResult;
import com.bolsadeploy.infrastructure.ssh.HostKeyPrompt;
import com.bolsadeploy.infrastructure.ssh.SshConnection;
import com.bolsadeploy.infrastructure.ssh.SshException;
import com.bolsadeploy.security.CommandValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Servicio integral para diagnóstico, administración, consultas y optimización
 * de motores y bases de datos remotas en VPS (MySQL, PostgreSQL, SQLite, Redis).
 */
public class DatabaseManagerService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManagerService.class);
    private final AppContext ctx;

    public DatabaseManagerService(AppContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Diagnostica los motores de base de datos instalados en el VPS.
     */
    public List<DatabaseInfo.EngineStatus> diagnosticarMotores(VpsServer vps) throws SshException {
        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            List<DatabaseInfo.EngineStatus> lista = new ArrayList<>();

            // 1. MySQL / MariaDB
            CommandResult rMysql = ssh.ejecutar(
                    "systemctl is-active mysql 2>/dev/null || systemctl is-active mariadb 2>/dev/null", 15);
            boolean mysqlActivo = "active".equalsIgnoreCase(rMysql.primeraLinea().trim());
            CommandResult vMysql = ssh.ejecutar("mysql --version 2>/dev/null", 15);
            lista.add(new DatabaseInfo.EngineStatus(
                    "MySQL / MariaDB",
                    mysqlActivo,
                    rMysql.primeraLinea().trim(),
                    3306,
                    vMysql.primeraLinea().trim(),
                    "mysql CLI"));

            // 2. PostgreSQL
            CommandResult rPg = ssh.ejecutar("systemctl is-active postgresql 2>/dev/null", 15);
            boolean pgActivo = "active".equalsIgnoreCase(rPg.primeraLinea().trim());
            CommandResult vPg = ssh.ejecutar("psql --version 2>/dev/null", 15);
            lista.add(new DatabaseInfo.EngineStatus(
                    "PostgreSQL",
                    pgActivo,
                    rPg.primeraLinea().trim(),
                    5432,
                    vPg.primeraLinea().trim(),
                    "psql CLI"));

            // 3. SQLite
            CommandResult rSqlite = ssh.ejecutar("which sqlite3 2>/dev/null", 15);
            boolean sqliteInstalado = !rSqlite.primeraLinea().isBlank();
            CommandResult vSqlite = ssh.ejecutar("sqlite3 --version 2>/dev/null", 15);
            lista.add(new DatabaseInfo.EngineStatus(
                    "SQLite",
                    sqliteInstalado,
                    sqliteInstalado ? "installed" : "inactive",
                    0,
                    vSqlite.primeraLinea().trim(),
                    "sqlite3 CLI"));

            // 4. Redis
            CommandResult rRedis = ssh.ejecutar(
                    "systemctl is-active redis-server 2>/dev/null || systemctl is-active redis 2>/dev/null", 15);
            boolean redisActivo = "active".equalsIgnoreCase(rRedis.primeraLinea().trim());
            CommandResult vRedis = ssh.ejecutar("redis-cli -v 2>/dev/null", 15);
            lista.add(new DatabaseInfo.EngineStatus(
                    "Redis (Caché & Sesiones)",
                    redisActivo,
                    rRedis.primeraLinea().trim(),
                    6379,
                    vRedis.primeraLinea().trim(),
                    "redis-cli"));

            return lista;
        }
    }

    /**
     * Lista todas las bases de datos presentes en el VPS correspondientes a los motores activos.
     */
    public List<DatabaseInfo> listarBasesDatos(VpsServer vps, List<Institution> instituciones) throws SshException {
        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            List<DatabaseInfo> bases = new ArrayList<>();

            // -------------------------------------------------- 1. MySQL / MariaDB
            try {
                CommandResult resMysql = ssh.ejecutar(
                        "sudo mysql -N -e \"SELECT table_schema, count(table_name), coalesce(sum(data_length + index_length),0) "
                                + "FROM information_schema.tables "
                                + "WHERE table_schema NOT IN ('information_schema','performance_schema','sys','mysql') "
                                + "GROUP BY table_schema;\" 2>/dev/null", 30);

                Set<String> detectadas = new HashSet<>();
                for (String linea : resMysql.lineas()) {
                    if (linea.isBlank()) continue;
                    String[] cols = linea.split("\\t");
                    if (cols.length >= 3) {
                        String nombre = cols[0].trim();
                        int tablas = (int) aLong(cols[1].trim());
                        long bytes = aLong(cols[2].trim());
                        detectadas.add(nombre);
                        bases.add(new DatabaseInfo("MYSQL", nombre, "", bytes, formatearBytes(bytes), tablas,
                                buscarProyectoAsociado(nombre, "", instituciones)));
                    }
                }

                // Detectar bases vacías sin tablas
                CommandResult allDb = ssh.ejecutar(
                        "sudo mysql -N -e \"SHOW DATABASES;\" 2>/dev/null | grep -vE '^(information_schema|performance_schema|sys|mysql)$'", 30);
                for (String l : allDb.lineas()) {
                    String nombre = l.trim();
                    if (!nombre.isBlank() && !detectadas.contains(nombre)) {
                        bases.add(new DatabaseInfo("MYSQL", nombre, "", 0, "0 B", 0,
                                buscarProyectoAsociado(nombre, "", instituciones)));
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudieron listar bases MySQL: {}", e.getMessage());
            }

            // -------------------------------------------------- 2. PostgreSQL
            try {
                CommandResult resPg = ssh.ejecutar(
                        "sudo -u postgres psql -t -A -F '|' -c \""
                                + "SELECT datname, pg_database_size(datname) "
                                + "FROM pg_database "
                                + "WHERE datistemplate = false AND datname NOT IN ('postgres');\" 2>/dev/null", 30);

                for (String linea : resPg.lineas()) {
                    if (linea.isBlank()) continue;
                    String[] cols = linea.split("\\|");
                    if (cols.length >= 2) {
                        String nombre = cols[0].trim();
                        long bytes = aLong(cols[1].trim());

                        // Conteo de tablas
                        CommandResult countTablas = ssh.ejecutar(
                                "sudo -u postgres psql -d \"" + nombre + "\" -t -A -c \""
                                        + "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';\" 2>/dev/null", 15);
                        int tablas = (int) aLong(countTablas.primeraLinea().trim());

                        bases.add(new DatabaseInfo("POSTGRESQL", nombre, "", bytes, formatearBytes(bytes), tablas,
                                buscarProyectoAsociado(nombre, "", instituciones)));
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudieron listar bases PostgreSQL: {}", e.getMessage());
            }

            // -------------------------------------------------- 3. SQLite
            try {
                CommandResult resSqlite = ssh.ejecutar(
                        "find /var/www -type f \\( -name \"*.sqlite\" -o -name \"*.sqlite3\" -o -name \"*.db\" \\) "
                                + "-exec ls -l {} + 2>/dev/null", 30);

                for (String linea : resSqlite.lineas()) {
                    if (linea.isBlank()) continue;
                    String[] parts = linea.split("\\s+");
                    if (parts.length >= 9) {
                        long bytes = aLong(parts[4]);
                        String ruta = parts[parts.length - 1];
                        String nombre = Path.of(ruta).getFileName().toString();

                        CommandResult countT = ssh.ejecutar(
                                "sqlite3 \"" + ruta + "\" \"SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';\" 2>/dev/null", 15);
                        int tablas = (int) aLong(countT.primeraLinea().trim());

                        bases.add(new DatabaseInfo("SQLITE", nombre, ruta, bytes, formatearBytes(bytes), tablas,
                                buscarProyectoAsociado(nombre, ruta, instituciones)));
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudieron listar archivos SQLite: {}", e.getMessage());
            }

            return bases;
        }
    }

    /**
     * Lista las tablas e información de tamaño de una base de datos.
     */
    public List<DatabaseInfo.TableInfo> listarTablas(VpsServer vps, DatabaseInfo db) throws SshException {
        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            List<DatabaseInfo.TableInfo> tablas = new ArrayList<>();

            if ("MYSQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar(
                        "sudo mysql -N -e \"SELECT table_name, coalesce(table_rows,0), coalesce(data_length+index_length,0), coalesce(engine,'') "
                                + "FROM information_schema.tables WHERE table_schema = '" + db.getNombre() + "' ORDER BY table_name;\" 2>/dev/null", 30);
                for (String l : r.lineas()) {
                    if (l.isBlank()) continue;
                    String[] c = l.split("\\t");
                    if (c.length >= 4) {
                        long filas = aLong(c[1]);
                        long bytes = aLong(c[2]);
                        tablas.add(new DatabaseInfo.TableInfo(c[0].trim(), filas, bytes, formatearBytes(bytes), c[3].trim()));
                    }
                }
            } else if ("POSTGRESQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar(
                        "sudo -u postgres psql -d \"" + db.getNombre() + "\" -t -A -F '|' -c \""
                                + "SELECT table_name, 0, coalesce(pg_total_relation_size(quote_ident(table_name)),0), 'Postgres' "
                                + "FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;\" 2>/dev/null", 30);
                for (String l : r.lineas()) {
                    if (l.isBlank()) continue;
                    String[] c = l.split("\\|");
                    if (c.length >= 4) {
                        long bytes = aLong(c[2]);
                        tablas.add(new DatabaseInfo.TableInfo(c[0].trim(), 0, bytes, formatearBytes(bytes), "PostgreSQL"));
                    }
                }
            } else if ("SQLITE".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar(
                        "sqlite3 \"" + db.getRuta() + "\" \"SELECT name, 'SQLite' FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;\" 2>/dev/null", 30);
                for (String l : r.lineas()) {
                    if (l.isBlank()) continue;
                    String[] c = l.split("\\|");
                    String tName = c[0].trim();
                    CommandResult cf = ssh.ejecutar("sqlite3 \"" + db.getRuta() + "\" \"SELECT count(*) FROM \\\"" + tName + "\\\";\" 2>/dev/null", 15);
                    long filas = aLong(cf.primeraLinea().trim());
                    tablas.add(new DatabaseInfo.TableInfo(tName, filas, 0, "-", "SQLite"));
                }
            }

            return tablas;
        }
    }

    /**
     * Ejecuta una consulta SQL segura en la base de datos seleccionada.
     */
    public DatabaseInfo.SqlQueryResult ejecutarConsulta(VpsServer vps, DatabaseInfo db, String sql) throws SshException {
        if (sql == null || sql.isBlank()) {
            return new DatabaseInfo.SqlQueryResult(false, null, null, 0, 0, "Consulta vacía.");
        }

        String sqlLimpio = sql.trim().replace("\"", "\\\"");
        long inicio = System.currentTimeMillis();

        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            CommandResult cr;

            if ("MYSQL".equalsIgnoreCase(db.getMotor())) {
                cr = ssh.ejecutar("sudo mysql -B -e \"USE \\`" + db.getNombre() + "\\`; " + sqlLimpio + "\" 2>&1", 60);
            } else if ("POSTGRESQL".equalsIgnoreCase(db.getMotor())) {
                cr = ssh.ejecutar("sudo -u postgres psql -d \"" + db.getNombre() + "\" -A -F $'\\t' -c \"" + sqlLimpio + "\" 2>&1", 60);
            } else if ("SQLITE".equalsIgnoreCase(db.getMotor())) {
                cr = ssh.ejecutar("sqlite3 -header -separator $'\\t' \"" + db.getRuta() + "\" \"" + sqlLimpio + "\" 2>&1", 60);
            } else {
                return new DatabaseInfo.SqlQueryResult(false, null, null, 0, 0, "Motor no soportado para consultas SQL directas.");
            }

            long duracion = System.currentTimeMillis() - inicio;

            if (!cr.exito() && cr.salida().toLowerCase().contains("error")) {
                return new DatabaseInfo.SqlQueryResult(false, null, null, 0, duracion, cr.salida().trim());
            }

            List<String> lineas = cr.lineas();
            if (lineas.isEmpty()) {
                return new DatabaseInfo.SqlQueryResult(true, List.of("Resultado"), List.of(List.of("Instrucción ejecutada con éxito (sin salida tabular).")), 0, duracion, "Ejecutado con éxito.");
            }

            // Parsear TSV
            String[] cabecera = lineas.get(0).split("\\t");
            List<String> columnas = new ArrayList<>();
            for (String c : cabecera) columnas.add(c.trim());

            List<List<String>> filas = new ArrayList<>();
            for (int i = 1; i < lineas.size(); i++) {
                String l = lineas.get(i);
                if (l.isBlank()) continue;
                String[] vals = l.split("\\t", -1);
                List<String> fila = new ArrayList<>();
                for (String v : vals) fila.add(v);
                filas.add(fila);
                if (filas.size() >= 500) break; // Limitar previsualización a 500 registros
            }

            return new DatabaseInfo.SqlQueryResult(true, columnas, filas, filas.size(), duracion,
                    "Consulta completada: " + filas.size() + " filas obtenidas en " + duracion + " ms.");
        }
    }

    /**
     * Crea una nueva base de datos y usuario dedicado en el VPS.
     */
    public void crearBaseDatos(VpsServer vps, String motor, String dbName, String usuario, String password) throws Exception {
        CommandValidator.exigirNombreSql(dbName);
        if (usuario != null && !usuario.isBlank()) {
            CommandValidator.exigirNombreSql(usuario);
        }

        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            if ("MYSQL".equalsIgnoreCase(motor)) {
                String cmd = String.format(
                        "sudo mysql -e \"CREATE DATABASE IF NOT EXISTS \\`%s\\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; "
                                + "CREATE USER IF NOT EXISTS '%s'@'localhost' IDENTIFIED BY '%s'; "
                                + "GRANT ALL PRIVILEGES ON \\`%s\\`.* TO '%s'@'localhost'; FLUSH PRIVILEGES;\"",
                        dbName, usuario, password.replace("'", "\\'"), dbName, usuario);
                CommandResult r = ssh.ejecutar(cmd, 30);
                if (!r.exito()) throw new Exception("Error al crear BD MySQL: " + r.salida());
            } else if ("POSTGRESQL".equalsIgnoreCase(motor)) {
                String cmdUser = String.format(
                        "sudo -u postgres psql -c \"DO \\$\\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%s') "
                                + "THEN CREATE USER %s WITH PASSWORD '%s'; END IF; END \\$\\$;\"",
                        usuario, usuario, password.replace("'", "''"));
                ssh.ejecutar(cmdUser, 30);

                String cmdDb = String.format("sudo -u postgres psql -c \"CREATE DATABASE %s OWNER %s;\"", dbName, usuario);
                CommandResult r = ssh.ejecutar(cmdDb, 30);
                if (!r.exito()) throw new Exception("Error al crear BD PostgreSQL: " + r.salida());
            } else {
                throw new IllegalArgumentException("Motor no soportado para creación automática: " + motor);
            }
        }
    }

    /**
     * Optimiza y repara una base de datos existente.
     */
    public String optimizarBaseDatos(VpsServer vps, DatabaseInfo db) throws SshException {
        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            if ("MYSQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sudo mysqlcheck --optimize \"" + db.getNombre() + "\" 2>&1", 120);
                return r.salida().trim();
            } else if ("POSTGRESQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sudo -u postgres psql -d \"" + db.getNombre() + "\" -c \"VACUUM (VERBOSE, ANALYZE);\" 2>&1", 120);
                return r.salida().trim();
            } else if ("SQLITE".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sqlite3 \"" + db.getRuta() + "\" \"VACUUM; PRAGMA optimize;\" 2>&1", 60);
                return r.salida().isBlank() ? "Base SQLite optimizada y compactada correctamente." : r.salida().trim();
            }
            return "Operación no soportada.";
        }
    }

    /**
     * Elimina una base de datos remota de forma definitiva.
     */
    public void eliminarBaseDatos(VpsServer vps, DatabaseInfo db) throws Exception {
        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            if ("MYSQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sudo mysql -e \"DROP DATABASE IF EXISTS \\`" + db.getNombre() + "\\`;\" 2>&1", 30);
                if (!r.exito()) throw new Exception(r.salida());
            } else if ("POSTGRESQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sudo -u postgres psql -c \"DROP DATABASE IF EXISTS " + db.getNombre() + ";\" 2>&1", 30);
                if (!r.exito()) throw new Exception(r.salida());
            } else if ("SQLITE".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("rm -f \"" + db.getRuta() + "\"", 30);
                if (!r.exito()) throw new Exception(r.salida());
            }
        }
    }

    /**
     * Exporta un volcado completo de la base de datos y lo descarga a la máquina local.
     */
    public Path exportarDump(VpsServer vps, DatabaseInfo db, Path carpetaDestinoLocal) throws Exception {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String remotoGz = "/tmp/vpspilot-" + db.getNombre() + "-" + timestamp + ".sql.gz";

        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            if ("MYSQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sudo mysqldump --single-transaction --quick \"" + db.getNombre() + "\" | gzip > " + remotoGz, 180);
                if (!r.exito()) throw new Exception("Error al exportar MySQL: " + r.salida());
            } else if ("POSTGRESQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sudo -u postgres pg_dump \"" + db.getNombre() + "\" | gzip > " + remotoGz, 180);
                if (!r.exito()) throw new Exception("Error al exportar PostgreSQL: " + r.salida());
            } else if ("SQLITE".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar("sqlite3 \"" + db.getRuta() + "\" .dump | gzip > " + remotoGz, 120);
                if (!r.exito()) throw new Exception("Error al exportar SQLite: " + r.salida());
            } else {
                throw new IllegalArgumentException("Motor no soportado para volcado.");
            }

            Files.createDirectories(carpetaDestinoLocal);
            Path archivoLocal = carpetaDestinoLocal.resolve(db.getMotor().toLowerCase() + "_" + db.getNombre() + "_" + timestamp + ".sql.gz");
            ssh.descargar(remotoGz, archivoLocal);
            ssh.ejecutar("rm -f " + remotoGz, 15);
            return archivoLocal;
        }
    }

    /**
     * Importa un archivo SQL o SQL.GZ local en la base de datos remota.
     */
    public void importarDump(VpsServer vps, DatabaseInfo db, Path archivoLocal) throws Exception {
        if (!Files.exists(archivoLocal)) {
            throw new IllegalArgumentException("El archivo local no existe: " + archivoLocal);
        }

        String nombreRemoto = "/tmp/vpspilot-import-" + System.currentTimeMillis() + "_" + archivoLocal.getFileName().toString();
        boolean esGz = archivoLocal.getFileName().toString().toLowerCase().endsWith(".gz");

        try (SshConnection ssh = ctx.conectar(vps, HostKeyPrompt.ACEPTAR_TODO)) {
            ssh.subir(archivoLocal, nombreRemoto);

            String cmdDescomprimir = esGz ? "gzip -dc " + nombreRemoto : "cat " + nombreRemoto;

            if ("MYSQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar(cmdDescomprimir + " | sudo mysql \"" + db.getNombre() + "\" 2>&1", 300);
                ssh.ejecutar("rm -f " + nombreRemoto, 15);
                if (!r.exito()) throw new Exception("Error al importar en MySQL: " + r.salida());
            } else if ("POSTGRESQL".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar(cmdDescomprimir + " | sudo -u postgres psql -d \"" + db.getNombre() + "\" 2>&1", 300);
                ssh.ejecutar("rm -f " + nombreRemoto, 15);
                if (!r.exito()) throw new Exception("Error al importar en PostgreSQL: " + r.salida());
            } else if ("SQLITE".equalsIgnoreCase(db.getMotor())) {
                CommandResult r = ssh.ejecutar(cmdDescomprimir + " | sqlite3 \"" + db.getRuta() + "\" 2>&1", 180);
                ssh.ejecutar("rm -f " + nombreRemoto, 15);
                if (!r.exito()) throw new Exception("Error al importar en SQLite: " + r.salida());
            }
        }
    }

    private static String buscarProyectoAsociado(String dbName, String dbPath, List<Institution> instituciones) {
        if (instituciones == null || instituciones.isEmpty()) return "-";
        for (Institution inst : instituciones) {
            if (inst.getNombreBaseDatos() != null && !inst.getNombreBaseDatos().isBlank()
                    && inst.getNombreBaseDatos().equalsIgnoreCase(dbName)) {
                return inst.getNombre();
            }
            if (!dbPath.isBlank() && inst.getRutaInstalacion() != null
                    && dbPath.contains(inst.getSlug())) {
                return inst.getNombre();
            }
        }
        return "-";
    }

    private static long aLong(String s) {
        if (s == null || s.isBlank()) return 0;
        String limpio = s.replaceAll("[^0-9]", "");
        if (limpio.isEmpty()) return 0;
        try {
            return Long.parseLong(limpio);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatearBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] unidades = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= unidades.length) digitGroups = unidades.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), unidades[digitGroups]);
    }
}

