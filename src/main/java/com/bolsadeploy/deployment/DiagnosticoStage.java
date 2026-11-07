package com.bolsadeploy.deployment;

import com.bolsadeploy.domain.CheckLevel;
import com.bolsadeploy.domain.DiagnosticCheck;
import com.bolsadeploy.infrastructure.ssh.CommandResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Etapa 1 — Diagnóstico del servidor.
 *
 * Detecta el estado real del VPS antes de tocar nada: SO y versión, recursos,
 * permisos, servicios ya instalados (PHP, MySQL, Nginx, Node, Composer),
 * paneles de hosting, DNS del dominio y estado del puerto 80.
 *
 * Guarda los hallazgos en {@code ctx.valores} para que las etapas posteriores
 * usen rutas y versiones reales (no suposiciones). Si el servidor es
 * incompatible (no Ubuntu 22.04/24.04, recursos insuficientes o sin permisos),
 * la instalación se detiene con la causa explicada.
 */
public class DiagnosticoStage implements DeploymentStage {

    private final List<DiagnosticCheck> checks = new ArrayList<>();

    @Override
    public String id() {
        return "diagnostico";
    }

    @Override
    public String nombre() {
        return "Diagnóstico del servidor";
    }

    /** Hallazgos de la última ejecución (para el reporte y la UI). */
    public List<DiagnosticCheck> getChecks() {
        return checks;
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        return false; // el diagnóstico siempre se ejecuta (es rápido y barato)
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        checks.clear();
        ctx.log("  Leyendo estado real del servidor…");

        // ---------------------------------------------------------------- SO
        CommandResult so = ctx.ejecutar(
                ". /etc/os-release 2>/dev/null && printf '%s|%s|%s' \"$ID\" \"$VERSION_ID\" \"${VERSION_CODENAME:-}\"",
                30);
        String[] soPartes = so.primeraLinea().split("\\|");
        String idSo = soPartes.length > 0 ? soPartes[0].trim().toLowerCase() : "";
        String versionSo = soPartes.length > 1 ? soPartes[1].trim() : "";
        String codename = soPartes.length > 2 ? soPartes[2].trim() : "";
        ctx.valores.put("so", idSo);
        ctx.valores.put("soVersion", versionSo);
        ctx.valores.put("codename", codename);

        if (!"ubuntu".equals(idSo)) {
            checks.add(DiagnosticCheck.error("Sistema operativo",
                    "Se requiere Ubuntu; se detectó '" + (idSo.isBlank() ? "desconocido" : idSo) + "'"));
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "El servidor no ejecuta Ubuntu (detectado: '" + idSo + "'). "
                            + "BolsaDeploy Manager solo gestiona VPS Ubuntu 22.04 o 24.04.");
        }
        boolean soSoportado = "22.04".equals(versionSo) || "24.04".equals(versionSo);
        if (soSoportado) {
            checks.add(DiagnosticCheck.ok("Sistema operativo", "Ubuntu " + versionSo + " (" + codename + ")"));
        } else {
            checks.add(DiagnosticCheck.warn("Sistema operativo",
                    "Ubuntu " + versionSo + " no está en la lista soportada (22.04/24.04); "
                            + "se usará la estrategia de PHP por defecto de esta versión"));
        }
        String phpPorDefecto = "22.04".equals(versionSo) ? "8.2" : "8.3";
        ctx.valores.put("phpObjetivo", phpPorDefecto);

        // -------------------------------------------------------------- Arquitectura
        String arch = ctx.ejecutar("uname -m", 20).primeraLinea().trim();
        ctx.valores.put("arch", arch);
        checks.add(DiagnosticCheck.ok("Arquitectura", arch));

        // ---------------------------------------------------------- Permisos/root
        String uid = ctx.ejecutar("id -u", 20).primeraLinea().trim();
        boolean esRoot = "0".equals(uid);
        ctx.valores.put("esRoot", String.valueOf(esRoot));
        if (esRoot) {
            checks.add(DiagnosticCheck.ok("Permisos", "Conectado como root"));
        } else {
            CommandResult sudoOk = ctx.ejecutar("sudo -n true", 20);
            if (sudoOk.exito()) {
                checks.add(DiagnosticCheck.ok("Permisos",
                        "Usuario sin root; se usará sudo sin contraseña (usuario: "
                                + ctx.ssh.getUsuario() + ")"));
            } else {
                checks.add(DiagnosticCheck.error("Permisos",
                        "El usuario '" + ctx.ssh.getUsuario() + "' no es root y sudo no funciona sin contraseña"));
                throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                        "No hay permisos para administrar el servidor: el usuario '" + ctx.ssh.getUsuario()
                                + "' no es root y 'sudo -n' falló. Configure sudo sin contraseña para ese "
                                + "usuario o conéctese como root.");
            }
        }

        // ------------------------------------------------------------- Recursos
        long memLivre = aEntero(ctx.ejecutar("free -m | awk '/^Mem:/{print $7}'", 30).primeraLinea());
        ctx.valores.put("memDisponibleMb", String.valueOf(memLivre));
        if (memLivre >= 1024) {
            checks.add(DiagnosticCheck.ok("Memoria disponible", memLivre + " MB"));
        } else if (memLivre >= 768) {
            checks.add(DiagnosticCheck.warn("Memoria disponible",
                    memLivre + " MB (justo para compilar frontend; cierre otras tareas)"));
        } else {
            checks.add(DiagnosticCheck.error("Memoria disponible",
                    memLivre + " MB; se necesitan al menos 768 MB libres"));
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "Memoria disponible insuficiente (" + memLivre + " MB). La instalación necesita al menos "
                            + "768 MB libres para compilar el frontend y ejecutar MySQL.");
        }

        long discoLibre = aEntero(ctx.ejecutar("df -BM --output=avail / | tail -n1 | tr -dc '0-9'", 30).primeraLinea());
        ctx.valores.put("discoLibreMb", String.valueOf(discoLibre));
        if (discoLibre >= 5120) {
            checks.add(DiagnosticCheck.ok("Disco disponible", (discoLibre / 1024) + " GB"));
        } else if (discoLibre >= 2048) {
            checks.add(DiagnosticCheck.warn("Disco disponible",
                    (discoLibre / 1024) + " GB (apretado para builds y respaldos)"));
        } else {
            checks.add(DiagnosticCheck.error("Disco disponible",
                    (discoLibre / 1024) + " GB; se necesitan al menos 2 GB"));
            throw new StageException(StageException.Tipo.REQUIERE_USUARIO,
                    "Disco insuficiente: quedan " + (discoLibre / 1024)
                            + " GB en /. Libere espacio antes de instalar.");
        }

        // ---------------------------------------------------------- IP pública
        String ip = ctx.ejecutar("{ curl -4 -s -m 8 https://api.ipify.org || curl -4 -s -m 8 https://icanhazip.com; } "
                + "| tr -d '\\n'", 30).primeraLinea().trim();
        ctx.valores.put("ipPublica", ip);
        checks.add(ip.isBlank()
                ? DiagnosticCheck.warn("IP pública", "No se pudo determinar (sin acceso saliente a internet)")
                : DiagnosticCheck.ok("IP pública", ip));

        // ---------------------------------------------------------- Estado actual
        instalarDetectado(ctx);

        // ---------------------------------------------------------------- DNS
        String dominio = ctx.institucion.getDominio();
        if (dominio != null && !dominio.isBlank()) {
            CommandResult dns = ctx.ejecutar("getent ahosts " + dominio + " | awk '{print $1}' | sort -u | head -5", 30);
            String apuntan = dns.salida().replaceAll("\\s+", " ").trim();
            if (apuntan.isEmpty()) {
                checks.add(DiagnosticCheck.warn("DNS de " + dominio,
                        "No resuelve todavía; el sitio quedará en HTTP y SSL se omitirá"));
            } else {
                boolean coincide = !ip.isBlank() && apuntan.contains(ip);
                checks.add(coincide
                        ? DiagnosticCheck.ok("DNS de " + dominio, "Apunta a la IP del servidor (" + ip + ")")
                        : DiagnosticCheck.warn("DNS de " + dominio,
                        "Resuelve a [" + apuntan + "] pero la IP del servidor es '"
                                + ip + "'; revise el registro A antes de pedir SSL"));
            }
        }

        // --------------------------------------------------- Puerto 80 ocupado?
        CommandResult puerto80 = ctx.ejecutar(
                "ss -lnt 2>/dev/null | awk '{print $4}' | grep -E ':(80|443)$' | sort -u | tr '\\n' ' '", 30);
        String ocupados = puerto80.salida().trim();
        if (!ocupados.isBlank()) {
            checks.add(DiagnosticCheck.warn("Puertos 80/443",
                    "Ya hay servicios escuchando (" + ocupados.trim() + "). Si es un sitio existente, "
                            + "BolsaDeploy no lo tocará: solo añade su propio vhost."));
            ctx.valores.put("puertos80443", "ocupados");
        } else {
            checks.add(DiagnosticCheck.ok("Puertos 80/443", "libres"));
            ctx.valores.put("puertos80443", "libres");
        }

        // ---------------------------------------------------------------- Paneles
        String panel = ctx.ejecutar(
                "ls -d /usr/local/cpanel /opt/psa /etc/cyberpanel /usr/local/vesta /usr/local/directadmin 2>/dev/null "
                        + "| head -1", 30).primeraLinea().trim();
        if (panel.isBlank()) {
            checks.add(DiagnosticCheck.ok("Panel de hosting", "Ninguno detectado (Ubuntu limpio)"));
            ctx.valores.put("panel", "");
        } else {
            checks.add(DiagnosticCheck.warn("Panel de hosting",
                    "Detectado en " + panel + "; puede haber servicios con configuración propia"));
            ctx.valores.put("panel", panel);
        }

        // -------------------------------------------------------- Cron / timers
        if (ctx.isCancelado()) {
            throw new StageException(StageException.Tipo.CANCELADO, "Operación cancelada por el usuario.");
        }

        // Resumen
        for (DiagnosticCheck c : checks) {
            ctx.log("  " + nivel(c.nivel()) + " " + c.nombre() + ": " + c.detalle());
        }
        ctx.valores.put("diagnosticoHecho", "true");
    }

    // ------------------------------------------------------------------

    /** Detecta versiones instaladas de PHP, MySQL, Nginx, Node, Composer… */
    private void instalarDetectado(DeploymentContext ctx) throws StageException {
        // PHP
        String php = ctx.ejecutar("php -r 'echo PHP_VERSION;' 2>/dev/null", 30).primeraLinea().trim();
        if (php.isBlank()) {
            ctx.valores.put("phpInstalado", "false");
            checks.add(DiagnosticCheck.warn("PHP", "No instalado; se instalará php"
                    + ctx.valores.getOrDefault("phpObjetivo", "8.3")));
        } else {
            ctx.valores.put("phpInstalado", "true");
            String mayor = php.split("\\.")[0];
            String menor = php.split("\\.").length > 1 ? php.split("\\.")[1] : "0";
            ctx.valores.put("phpVersion", mayor + "." + menor);
            checks.add(CompareVersiones(mayor + "." + menor, "8.2") >= 0
                    ? DiagnosticCheck.ok("PHP", "PHP " + php)
                    : DiagnosticCheck.error("PHP", "PHP " + php + " es anterior a 8.2 (requerido por Laravel 12)"));
        }

        // Socket / servicio php-fpm
        String sockets = ctx.ejecutar("ls /run/php/ 2>/dev/null | tr '\\n' ' '", 30).primeraLinea().trim();
        String versionPhp = ctx.valores.getOrDefault("phpVersion", ctx.valores.get("phpObjetivo"));
        String fpmService = ctx.ejecutar(
                "systemctl list-unit-files --no-pager 2>/dev/null "
                        + "| awk '$1 ~ /^php[0-9.]*-fpm\\.service$/ {print $1}' | head -1", 30)
                .primeraLinea().trim();
        if (fpmService.isBlank()) {
            fpmService = "php" + versionPhp + "-fpm";
        }
        ctx.valores.put("phpFpmService", fpmService);
        if (sockets.contains(".sock")) {
            String socket = "unix:/run/php/" + sockets.split("\\s+")[0];
            ctx.valores.put("socketFpm", socket);
            checks.add(DiagnosticCheck.ok("PHP-FPM", "servicio " + fpmService + ", socket " + socket));
        } else {
            String socket = "unix:/run/php/php" + versionPhp + "-fpm.sock";
            ctx.valores.put("socketFpm", socket);
            checks.add(DiagnosticCheck.warn("PHP-FPM",
                    "servicio " + fpmService + " sin socket activo aún (se usará " + socket + ")"));
        }

        // MySQL / MariaDB
        String mysqlSvc = "";
        for (String candidato : new String[]{"mysql", "mariadb", "mysqld"}) {
            CommandResult r = ctx.ejecutar(
                    "systemctl list-unit-files --no-pager 2>/dev/null | grep -c '^" + candidato + "\\.service'", 30);
            if ("1".equals(r.primeraLinea().trim())) {
                mysqlSvc = candidato;
                break;
            }
        }
        if (mysqlSvc.isEmpty()) {
            ctx.valores.put("mysqlService", "mysql");
            checks.add(DiagnosticCheck.warn("MySQL", "No instalado; se instalará mysql-server"));
        } else {
            ctx.valores.put("mysqlService", mysqlSvc);
            String activo = ctx.ejecutar("systemctl is-active " + mysqlSvc + " 2>/dev/null", 30).primeraLinea().trim();
            checks.add("active".equals(activo)
                    ? DiagnosticCheck.ok("MySQL", "servicio " + mysqlSvc + " activo")
                    : DiagnosticCheck.warn("MySQL", "servicio " + mysqlSvc + " instalado pero '" + activo + "'"));
        }

        // Nginx
        String nginx = ctx.ejecutar("nginx -v 2>&1 | head -1", 30).primeraLinea().trim();
        if (nginx.isBlank() || nginx.contains("not found")) {
            ctx.valores.put("nginxInstalado", "false");
            checks.add(DiagnosticCheck.warn("Nginx", "No instalado; se instalará"));
        } else {
            ctx.valores.put("nginxInstalado", "true");
            checks.add(DiagnosticCheck.ok("Nginx", nginx.replace("nginx version: ", "")));
        }

        // Node
        String node = ctx.ejecutar("node -v 2>/dev/null", 30).primeraLinea().trim();
        if (node.isBlank()) {
            ctx.valores.put("nodeInstalado", "false");
            checks.add(DiagnosticCheck.warn("Node.js", "No instalado; se instalará Node 22 LTS"));
        } else {
            ctx.valores.put("nodeInstalado", "true");
            ctx.valores.put("nodeVersion", node);
            int mayorNode = (int) aEntero(node.replace("v", "").split("\\.")[0]);
            checks.add(mayorNode >= 22
                    ? DiagnosticCheck.ok("Node.js", node)
                    : DiagnosticCheck.warn("Node.js", node + " es anterior a 22; se instalará Node 22 LTS"));
        }

        // Composer / Git / Supervisor / Certbot
        String composer = ctx.ejecutar("composer --version 2>/dev/null | head -1", 40).primeraLinea().trim();
        ctx.valores.put("composerInstalado", String.valueOf(!composer.isBlank()));
        checks.add(composer.isBlank()
                ? DiagnosticCheck.warn("Composer", "No instalado; se instalará")
                : DiagnosticCheck.ok("Composer", composer));

        String git = ctx.ejecutar("git --version 2>/dev/null", 30).primeraLinea().trim();
        ctx.valores.put("gitInstalado", String.valueOf(!git.isBlank()));
        checks.add(git.isBlank()
                ? DiagnosticCheck.warn("Git", "No instalado; se instalará")
                : DiagnosticCheck.ok("Git", git));

        String supervisor = ctx.ejecutar("command -v supervisorctl || true", 30).primeraLinea().trim();
        ctx.valores.put("supervisorInstalado", String.valueOf(!supervisor.isBlank()));
        checks.add(supervisor.isBlank()
                ? DiagnosticCheck.warn("Supervisor", "No instalado; se instalará para las colas")
                : DiagnosticCheck.ok("Supervisor", "supervisorctl disponible"));

        String certbot = ctx.ejecutar("command -v certbot || true", 30).primeraLinea().trim();
        ctx.valores.put("certbotInstalado", String.valueOf(!certbot.isBlank()));
        checks.add(certbot.isBlank()
                ? DiagnosticCheck.warn("Certbot", "No instalado; se usará solo si hay HTTPS")
                : DiagnosticCheck.ok("Certbot", certbot));
    }

    private static int CompareVersiones(String a, String b) {
        try {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
                int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (va != vb) {
                    return Integer.compare(va, vb);
                }
            }
            return 0;
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static String nivel(CheckLevel l) {
        return switch (l) {
            case CORRECTO -> "[i]";
            case ADVERTENCIA -> "[!]";
            case ERROR -> "[X]";
            case NO_EVALUADO -> "[?]";
        };
    }
}
