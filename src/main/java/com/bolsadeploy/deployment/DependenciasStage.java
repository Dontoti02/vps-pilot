package com.bolsadeploy.deployment;

import com.bolsadeploy.infrastructure.ssh.CommandResult;

/**
 * Etapa 2 — Dependencias del sistema.
 *
 * Instala por apt (idempotente, sin prompts) todo lo que el sistema necesita:
 * utilidades base, PHP según la versión de Ubuntu (php8.3 oficial en 24.04;
 * PPA ondrej php8.2 en 22.04), MySQL, Nginx, Node.js 22 LTS desde el repositorio
 * firmado de NodeSource, Composer, Supervisor y —opcional— Redis.
 *
 * Nunca usa {@code curl | bash}: los repositorios de Node se añaden con su
 * clave GPG verificable.
 */
public class DependenciasStage implements DeploymentStage {

    @Override
    public String id() {
        return "dependencias";
    }

    @Override
    public String nombre() {
        return "Dependencias del sistema (PHP, MySQL, Nginx, Node)";
    }

    @Override
    public boolean yaCumplida(DeploymentContext ctx) {
        try {
            // Todo lo esencial ya instalado y en versión válida → no tocar apt
            boolean phpOk = "true".equals(ctx.valores.get("phpInstalado"))
                    && compara(ctx.valores.getOrDefault("phpVersion", "0"), "8.2") >= 0;
            boolean nodeOk = "true".equals(ctx.valores.get("nodeInstalado"))
                    && compara(ctx.valores.getOrDefault("nodeVersion", "v0"), "v22") >= 0;
            boolean mysqlOk = ctx.ejecutar(
                    "systemctl list-unit-files --no-pager 2>/dev/null | grep -c '^" + ctx.servicioMysql() + "\\.service'",
                    30).primeraLinea().trim().equals("1");
            boolean nginxOk = "true".equals(ctx.valores.get("nginxInstalado"));
            boolean composerOk = "true".equals(ctx.valores.get("composerInstalado"));
            boolean gitOk = "true".equals(ctx.valores.get("gitInstalado"));
            boolean supervisorOk = "true".equals(ctx.valores.get("supervisorInstalado"));
            return phpOk && nodeOk && mysqlOk && nginxOk && composerOk && gitOk && supervisorOk;
        } catch (StageException e) {
            return false;
        }
    }

    @Override
    public void ejecutar(DeploymentContext ctx) throws StageException {
        String objetivo = ctx.phpObjetivo();
        ctx.log("  Ubuntu " + ctx.valores.getOrDefault("soVersion", "?") + " → objetivo PHP " + objetivo);

        // -------------------------------------------------- Paquetes base
        ctx.log("  Actualizando índices de paquetes…");
        ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get update -y"), 600,
                "apt-get update");
        ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "
                + "curl wget git unzip zip ca-certificates gnupg lsb-release software-properties-common acl"), 600,
                "Instalación de utilidades base");

        // ---------------------------------------------------------------- PHP
        instalarPhp(ctx, objetivo);

        // --------------------------------------------------------------- MySQL
        if (!"true".equals(ctx.valores.get("mysqlInstalado"))) {
            CommandResult r = ctx.ejecutar("systemctl list-unit-files --no-pager 2>/dev/null | grep -c '^"
                    + ctx.servicioMysql() + "\\.service'", 30);
            if (!"1".equals(r.primeraLinea().trim())) {
                ctx.log("  Instalando MySQL Server…");
                ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold mysql-server"),
                        900, "Instalación de MySQL Server");
            }
        }
        ctx.exigirExito(ctx.sudo("systemctl enable --now " + ctx.servicioMysql()), 120,
                "Activación del servicio " + ctx.servicioMysql());
        ctx.ok("MySQL (" + ctx.servicioMysql() + ") activo");

        // -------------------------------------------------------------- Nginx
        if (!"true".equals(ctx.valores.get("nginxInstalado"))) {
            ctx.log("  Instalando Nginx…");
            ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold nginx"),
                    600, "Instalación de Nginx");
        }
        ctx.exigirExito(ctx.sudo("systemctl enable --now nginx"), 120, "Activación de Nginx");
        ctx.ok("Nginx activo");

        // ------------------------------------------------------- Node.js 22
        String node = ctx.ejecutar("node -v 2>/dev/null", 30).primeraLinea().trim();
        int mayorNode = 0;
        try {
            mayorNode = Integer.parseInt(node.replace("v", "").split("\\.")[0]);
        } catch (Exception ignored) {
        }
        if (mayorNode < 22) {
            ctx.log("  Instalando Node.js 22 LTS (repositorio firmado de NodeSource)…");
            ctx.exigirExito("mkdir -p /etc/apt/keyrings", 30, "Creación de /etc/apt/keyrings");
            ctx.exigirExito(ctx.sudo("curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key "
                    + "| gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg"), 180,
                    "Descarga de la clave GPG de NodeSource");
            ctx.exigirExito(ctx.sudo("sh -c 'echo \"deb [signed-by=/etc/apt/keyrings/nodesource.gpg] "
                    + "https://deb.nodesource.com/node_22.x nodistro main\" > /etc/apt/sources.list.d/nodesource.list'"),
                    30, "Registro del repositorio de Node 22");
            ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get update -y"), 600,
                    "apt-get update (tras añadir NodeSource)");
            ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y nodejs"), 900,
                    "Instalación de Node.js");
        }
        String nodeFinal = ctx.ejecutar("node -v 2>/dev/null", 30).primeraLinea().trim();
        if (aEntero(nodeFinal) < 22) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Node.js quedó en " + (nodeFinal.isBlank() ? "(sin instalar)" : nodeFinal)
                            + "; se necesitaba v22 o superior para compilar Vite 7.");
        }
        ctx.valores.put("nodeVersion", nodeFinal);
        ctx.ok("Node.js " + nodeFinal);

        // ------------------------------------------------------------ Composer
        String composer = ctx.ejecutar("composer --version 2>/dev/null | head -1", 40).primeraLinea().trim();
        if (composer.isBlank()) {
            ctx.log("  Instalando Composer…");
            ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y composer"), 600,
                    "Instalación de Composer");
            composer = ctx.ejecutar("composer --version 2>/dev/null | head -1", 40).primeraLinea().trim();
        }
        if (composer.isBlank() || !composer.startsWith("Composer 2")) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Composer 2 no está disponible (detectado: '"
                            + (composer.isBlank() ? "nada" : composer) + "'). Laravel 12 lo requiere.");
        }
        ctx.ok(composer);

        // ---------------------------------------------------------- Supervisor
        if (!"true".equals(ctx.valores.get("supervisorInstalado"))) {
            ctx.log("  Instalando Supervisor (colas de Laravel)…");
            ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y supervisor"),
                    600, "Instalación de Supervisor");
        }
        ctx.exigirExito(ctx.sudo("systemctl enable --now supervisor"), 120, "Activación de Supervisor");
        ctx.ok("Supervisor activo");

        // ------------------------------------------------------ Redis (opcional)
        if (ctx.instalarRedis) {
            ctx.log("  Instalando Redis (opcional)…");
            CommandResult redis = ctx.ejecutar(ctx.sudo("export DEBIAN_FRONTEND=noninteractive "
                    + "&& apt-get install -y redis-server"), 600);
            if (redis.exito()) {
                ctx.ejecutar(ctx.sudo("systemctl enable --now redis-server"), 120);
                ctx.valores.put("redisInstalado", "true");
                ctx.ok("Redis activo");
            } else {
                ctx.valores.put("redisInstalado", "false");
                ctx.log("  [!] Redis no se pudo instalar; se mantendrán colas/caché en base de datos (válido).");
            }
        }

        // -------------------------------------------------- Extensión PHP redis
        String verPhp = ctx.valores.getOrDefault("phpVersion", objetivo);
        CommandResult extRedis = ctx.ejecutar(ctx.sudo("export DEBIAN_FRONTEND=noninteractive "
                + "&& apt-get install -y php" + verPhp + "-redis"), 300);
        if (!extRedis.exito()) {
            ctx.ejecutar(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y php-redis"), 300);
        }

        // ------------------------------------------- Extensiones PHP verificadas
        String faltantes = ctx.ejecutar(
                "php -r '$n=[\"pdo_mysql\",\"mbstring\",\"xml\",\"curl\",\"zip\",\"gd\",\"bcmath\",\"intl\",\"fileinfo\",\"tokenizer\"];"
                        + "$f=[];foreach($n as $e){if(!extension_loaded($e)){$f[]=$e;}}echo implode(\",\",$f);'", 60)
                .primeraLinea().trim();
        if (!faltantes.isBlank()) {
            throw new StageException(StageException.Tipo.FATAL,
                    "Faltan extensiones PHP obligatorias: " + faltantes + ". No se puede continuar.");
        }
        ctx.ok("Extensiones PHP verificadas (pdo_mysql, mbstring, xml, curl, zip, gd, bcmath, intl…)");
    }

    // ------------------------------------------------------------------

    private void instalarPhp(DeploymentContext ctx, String objetivo) throws StageException {
        String instalado = ctx.valores.getOrDefault("phpInstalado", "false");
        String version = ctx.valores.getOrDefault("phpVersion", "");
        boolean suficiente = "true".equals(instalado) && compara(version, "8.2") >= 0;

        if (suficiente && compara(version, objetivo) >= 0) {
            ctx.log("  PHP " + version + " ya instalado; no se modifica.");
            return;
        }
        if (suficiente) {
            // Hay PHP válido pero por debajo del objetivo: dejarlo (también sirve)
            ctx.log("  PHP " + version + " instalado y compatible; no se modifica.");
            return;
        }

        String codename = ctx.valores.getOrDefault("codename", "");
        if ("22.04".equals(ctx.valores.getOrDefault("soVersion", ""))) {
            ctx.log("  Añadiendo PPA ondrej/php (Ubuntu 22.04 trae PHP 8.1)…");
            ctx.exigirExito(ctx.sudo("add-apt-repository -y ppa:ondrej/php"), 600,
                    "Adición del PPA ondrej/php");
            ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get update -y"), 600,
                    "apt-get update (tras PPA PHP)");
        }

        String paquetes = "php" + objetivo + "-fpm php" + objetivo + "-cli php" + objetivo + "-mysql "
                + "php" + objetivo + "-mbstring php" + objetivo + "-xml php" + objetivo + "-curl "
                + "php" + objetivo + "-zip php" + objetivo + "-gd php" + objetivo + "-bcmath "
                + "php" + objetivo + "-intl php" + objetivo + "-sqlite3";
        ctx.log("  Instalando " + paquetes + "…");
        ctx.exigirExito(ctx.sudo("export DEBIAN_FRONTEND=noninteractive && apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "
                + paquetes), 900, "Instalación de PHP " + objetivo);

        // Fijar la alternativa por defecto (si conviven varias versiones)
        ctx.ejecutar(ctx.sudo("update-alternatives --set php /usr/bin/php" + objetivo + " 2>/dev/null"), 60);

        String versionFinal = ctx.ejecutar("php -r 'echo PHP_VERSION;' 2>/dev/null", 60).primeraLinea().trim();
        if (versionFinal.isBlank() || compara(versionFinal, "8.2") < 0) {
            throw new StageException(StageException.Tipo.FATAL,
                    "PHP quedó en '" + (versionFinal.isBlank() ? "sin versión" : versionFinal)
                            + "' tras la instalación; se requiere PHP 8.2 o superior.");
        }
        ctx.valores.put("phpInstalado", "true");
        ctx.valores.put("phpVersion", versionFinal.split("\\.")[0] + "." + versionFinal.split("\\.")[1]);
        ctx.ok("PHP " + versionFinal + " instalado");
        ctx.log("  codename detectado: " + codename);
    }

    private static int compara(String a, String b) {
        try {
            String[] pa = (a == null ? "" : a).replace("v", "").split("\\.");
            String[] pb = (b == null ? "" : b).replace("v", "").split("\\.");
            for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
                int va = i < pa.length ? Integer.parseInt(pa[i].replaceAll("[^0-9]", "")) : 0;
                int vb = i < pb.length ? Integer.parseInt(pb[i].replaceAll("[^0-9]", "")) : 0;
                if (va != vb) {
                    return Integer.compare(va, vb);
                }
            }
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long aEntero(String s) {
        try {
            return Long.parseLong(s.replace("v", "").split("\\.")[0]);
        } catch (Exception e) {
            return 0;
        }
    }
}
