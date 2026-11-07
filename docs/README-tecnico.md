# BolsaDeploy Manager — README técnico

Orquestador de despliegues, actualizaciones, respaldos y monitoreo del sistema
Laravel **bolsalaboralv2** en múltiples VPS Ubuntu, desde una aplicación de
escritorio Windows.

- Lenguaje: **Java 25** (LTS), **JavaFX** para la UI.
- Build: **Maven 3.9**.
- SSH: **SSHJ 0.39** con verificación estricta de host keys.
- Base local: **SQLite** (JDBC, WAL) + esquema versionado.
- JSON: **Jackson**. Logs: **SLF4J + Logback**. Pruebas: **JUnit 5 + Mockito**.
- Empaquetado: **jpackage** (app-image con runtime embebido; `.exe` con WiX 3).

---

## 1. Estructura del proyecto

```
BolsaDeployManager/
├── pom.xml                      Dependencias fijadas, mainClass, copy-dependencies
├── resources/icono.ico          Icono generado (scripts/gen-icono.ps1)
├── scripts/
│   ├── gen-base-sql.ps1         Genera src/main/resources/db/base_instalacion.sql
│   ├── gen-icono.ps1            Genera el .ico (GDI+, sin herramientas externas)
│   └── build.ps1                Compila + icono + jpackage (app-image/exe/zip)
├── docs/                        Auditoría, manual, validación, limitaciones
└── src/main/
    ├── java/com/bolsadeploy/
    │   ├── domain/              Entidades y value objects puros
    │   ├── security/            Credenciales cifradas, validación, hash bcrypt
    │   ├── infrastructure/
    │   │   ├── ssh/             Conexión SSHJ, host keys, subida/bajada
    │   │   ├── persistence/     SQLite + repositorios + migraciones de esquema
    │   │   └── github/          API REST de GitHub (compare, commits, archivos)
    │   ├── deployment/          11 etapas + runner + contexto + builders
    │   ├── updates/             Planificación, ejecución con rollback, masivo
    │   ├── backups/             Creación verificada, listado, descarga, restauración
    │   ├── monitoring/          Diagnóstico SSH + comprobación HTTP/TLS
    │   ├── logging/             Auditoría en SQLite con enmascaro de secretos
    │   ├── application/         AppContext (composition root) + servicios
    │   └── ui/                  JavaFX (vistas, navegación, diálogos, CSS)
    └── resources/
        ├── db/base_instalacion.sql   Esquema+datos base para instalación nueva
        └── ui/                       CSS tema oscuro/claro
```

### Flujo de dependencias

```
ui ──▶ application ──▶ { deployment, updates, backups, monitoring, logging }
                    └─▶ infrastructure { ssh, persistence, github }
                    └─▶ security, domain
```

`domain` y `security` no dependen de nada más que de JDK. `deployment` solo
depende de `domain`, `security` e `infrastructure/ssh`. La UI **nunca** habla
directo con SSH: todo pasa por `application` (`AppContext`, `DeploymentService`,
`MaintenanceService`), que es el único punto que abre/cierra conexiones y escribe
auditoría.

---

## 2. Modelo de datos local (SQLite)

Esquema versionado por `SchemaMigrator` (versión 1) en
`%APPDATA%\BolsaDeployManager\bolsadeploy.sqlite`:

- `vps_servers` — servidores registrados (sin secretos).
- `institutions` — instituciones: dominio, slug, base de datos, VPS asociado,
  estado (`PENDIENTE|INSTALANDO|INSTALADA|ACTUALIZANDO|ERROR`), commit desplegado,
  checkpoint de etapa.
- `operations` — historial/auditoría.
- `backups` — registros de respaldos.
- `app_settings` — ajustes (tema, repo, concurrencia, timeouts…).

**Los secretos jamás se guardan en SQLite.** Viven en
`credenciales.properties` cifrados con **DPAPI** (`CryptProtectData`, vinculado a
la cuenta de Windows) bajo identificadores:

| Identificador | Contenido |
|---|---|
| `vps-<id>` | Contraseña SSH o clave privada del VPS. |
| `inst-<id>-db` | Contraseña del usuario MySQL de la institución. |
| `inst-<id>-smtp` | Contraseña SMTP de la institución. |

En el VPS, las credenciales de MySQL viajan siempre en un archivo
`/tmp/bolsa-*.my.cnf` con permiso 600 que se elimina en un `finally` — nunca en
la línea de comandos.

---

## 3. Instalación remota (11 etapas)

Estructura por institución en el servidor:

```
/var/www/bolsadeploy/<slug>/
├── releases/<sha>/     Código clonado (una por versión)
├── current -> releases/<sha>   (enlace conmutado atómicamente con ln -sfn)
├── shared/
│   ├── .env            (nunca se reescribe en actualizaciones)
│   ├── storage/        (logs, caché, sesiones — persistente)
│   └── public/uploads/ (CVs y logos — persistente; el repo real se sustituye por enlace)
└── backups/            (respaldos en el propio VPS)
```

Etapas (`com.bolsadeploy.deployment`, todas con `yaCumplida()` para reintentos
idempotentes y `motivoOmitida()` para omisiones honestas):

| # | Etapa | Responsabilidad clave |
|---|-------|----------------------|
| 1 | `Diagnostico` | Ubuntu, sudo, red, disco, puertos, sitio existente. |
| 2 | `Dependencias` | nginx, PHP-FPM, MySQL/MariaDB, Composer, Node, Supervisor, Certbot. |
| 3 | `BaseDatos` | Crea BD/usuario (o adopta existente), importa `base_instalacion.sql`, `migrate --force`, INSERT admin con `LAST_INSERT_ID()`, EAV `system_configuration`. **Nunca** `db:seed`/`migrate:fresh`. |
| 4 | `DominioNginx` | vhost con `SCRIPT_FILENAME` antes del `try_files`, sitio en `/etc/nginx/sites-available`. |
| 5 | `Codigo` | `git clone` exacto de la rama/commit. |
| 6 | `LaravelEnv` | `.env` con `EnvFileBuilder`: APP_KEY generada **solo en instalación nueva**, APP_DEBUG=false, enlaces a `shared/`. |
| 7 | `DependenciasApp` | `composer install --no-dev`, `npm ci && npm run build`, `php artisan optimize`. |
| 8 | `Migracion` | `migrate --force` + cachés + permisos (sin 777: `www-data`, 750/640). |
| 9 | `Servicios` | systemd, programa Supervisor `bolsa-<slug>-worker` (verificado RUNNING), crontab de www-data fusionada por marcador `# bolsa-<slug>` (idempotente, no toca líneas ajenas). |
| 10 | `Ssl` | Certbot `--nginx` no interactivo; omisión explícita si HTTPS no solicitado o DNS pendiente; fallo real → `sslEstado=PENDIENTE` (nunca éxito falso). |
| 11 | `Validacion` | HTTP 200 **+ anclas de contenido** ("Oportunidades Laborales", campo `type="password"` en `/login`, sin Whoops/SQLSTATE), `APP_DEBUG=false`, artefactos, `migrate:status`, servicios. |

---

## 4. Actualizaciones (rollback garantizado)

```
aprobación de riesgo → respaldo INTEGRAL verificado → clone del SHA exacto
→ composer/npm en la release nueva → migrate --force → ln -sfn atómico
→ validación HTTP+contenido → [si falla: rollback a la release anterior + re-validación]
→ limpieza (conserva 3 releases)
```

- `UpdatePlanner` calcula el plan con la API real de GitHub (`compare`): commits
  pendientes, archivos con parche, migraciones pendientes y **riesgo destructivo**
  detectado por nombre de migración *y* por contenido del parche
  (`DROP TABLE|dropColumn|TRUNCATE|DELETE FROM|Schema::drop`).
- `UpdateExecutor` jamás ejecuta `migrate:fresh`/`db:wipe`, jamás regenera
  `APP_KEY` ni reescribe `shared/.env` (los enlaces shared garantizan la
  conservación de datos y archivos).
- Si falla la preparación antes de conmutar, el sitio sigue sirviendo la versión
  anterior y la release incompleta se elimina.
- `MassUpdateRunner` limita la concurrencia (`concurrenciaActualizaciones`) y
  aísla fallos por institución.

---

## 5. Compilación y pruebas

```powershell
# Desde BolsaDeployManager/
$env:JAVA_HOME = "E:\orq-bolsalaboral\tools\jdk-25.0.5+8"
$env:Path = "$env:JAVA_HOME\bin;E:\orq-bolsalaboral\tools\apache-maven-3.9.9\bin;$env:Path"

mvn -q -B compile          # solo compilar
mvn -B test                # pruebas unitarias/de integración
mvn -B package             # jar + target/lib con dependencias
```

Las pruebas usan SQLite temporal, `HttpServer` del JDK para el cliente de GitHub y
mocks de SSH donde corresponde (según §21 del prompt).

---

## 6. Empaquetado Windows (FASE H)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
# o sin pruebas:  ... -OmitirPruebas
```

El script:

1. `mvn clean package` (incluye pruebas salvo `-OmitirPruebas`).
2. Genera `resources/icono.ico` si falta.
3. `jpackage --type app-image` con **runtime Java 25 embebido** y `--class-path lib/*`
   → `target/dist/BolsaDeployManager/BolsaDeployManager.exe`.
4. Si `candle.exe` (WiX Toolset 3) está en el PATH → `jpackage --type exe` con menú
   de inicio y acceso directo. **Si WiX falta, se imprime un aviso explícito con lo
   necesario para obtener el `.exe`** (instalar WiX 3.14, añadir `bin` al PATH,
   reejecutar). El `.exe` generado contiene el programa **y** su runtime; el
   ejecutable de uso queda en la carpeta de instalación elegida.
5. `BolsaDeployManager-portatil.zip` con la app-image completa.

---

## 7. Decisiones y garantías de seguridad

- **Host keys**: verificación estricta (`StrictHostKeyVerifier` + `HostKeyStore`
  en formato `known_hosts`). Clave desconocida o **modificada** → diálogo explícito
  con la huella SHA256; jamás se acepta en silencio.
- **Auditoría**: cada operación queda en `operations` (SQLite) y en el log SLF4J;
  la salida pasa por `AuditLog.sinSecretos()` que enmascara `password=…`,
  `token=…`, etc.
- **Sin mensajes falsos**: cada estado mostrado proviene de un resultado verificado
  (código de salida + conteos + comprobaciones de contenido). Los fallos incluyen
  la acción correctiva.
- **Cancelación**: entre etapas (nunca se aborta un comando a medias); el checkpoint
  permite reanudar.
- **Validación de entrada**: `CommandValidator` valida dominios, slugs, rutas,
  emails, SHA, refs de git y escapa comandos antes de interpolación.

---

## 8. Limitaciones conocidas

Ver `docs/limitaciones.md`. La más importante: **no se ha ejecutado un despliegue
real contra un VPS Ubuntu en este entorno** (no hay servidor disponible); toda la
lógica remota está implementada y compilada, pero su validación en un servidor real
está pendiente y así se reporta en `docs/validacion.md`.
