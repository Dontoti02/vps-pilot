# FASE A — Auditoría del repositorio bolsalaboralv2

**Fecha:** 2026-11-06
**Repositorio:** https://github.com/Dontoti02/bolsalaboralv2.git
**Rama auditada:** `main` — commit `ad268b9` (*feat: Integrar consultas seguras a RENIEC y SUNAT…*)
**Copia local de auditoría:** `E:\orq-bolsalaboral\bolsalaboralv2-audit`

---

## 1. Stack verificado (contra el repo real)

| Componente | Versión real | Notas |
|---|---|---|
| Laravel | `^12.0` (composer.json) | Confirmado Laravel 12 |
| PHP | `^8.2` | Extensión requerida por composer |
| MySQL/MariaDB | Producción: MariaDB 11.8 (dump) | `.env.example` trae `sqlite` por defecto (desarrollo) |
| Vite | `^7.0.7` | Requiere Node ≥ 20.19 / 22.12+ |
| Tailwind CSS | `^4.0.0` | Plugin `@tailwindcss/vite` |
| Node (README guía) | Node 20 LTS (nodesource) | El prompt pide 22.12+ → usar Node 22 LTS |
| Composer | 2.x | |

**Paquetes composer relevantes:** `barryvdh/laravel-dompdf`, `maatwebsite/excel`, `phpoffice/phpspreadsheet`, `laravel/tinker` (en `require`, disponible en producción).

**Extensiones PHP efectivas** (según README + composer.lock): `pdo_mysql, mbstring, xml, curl, zip, gd, bcmath, intl, dom, tokenizer, fileinfo, redis (opcional)`.

## 2. Hallazgos críticos para el orquestador

### 2.1 El esquema NO se puede crear solo con `migrate` en una base nueva

- Los archivos actuales de `database/migrations/` (15 archivos) **solo crean tablas del framework** (`users`, `cache`, `jobs`, …) y **alteraciones** sobre tablas de dominio (`person`, `rol`, `job_opportunity_*`) que **no existen en una base vacía**.
- Las migraciones históricas que crean las tablas de dominio (`create_person_table`, `create_user_table`, `create_job_opportunity_*`, …) **fueron eliminadas del repositorio**; solo quedan registradas en la tabla `migrations` del dump.
- **Consecuencia:** `php artisan migrate` sobre base vacía **falla** (`Table 'person' doesn't exist`). El README (`migrate --seed`) no funciona en instalación real desde cero.

**Solución adoptada (FASE D):** el orquestador importa un **SQL base limpio** generado a partir del dump (estructura + datos de referencia) y luego ejecuta `php artisan migrate --force` (sin-op si todo está registrado; aplica migraciones futuras del repo).

### 2.2 El dump `db_blaboral.sql` contiene datos reales de OTRA institución

- 30 `CREATE TABLE`, 24 claves foráneas, **23 tablas con INSERT**.
- Incluye datos personales reales: `person` (50 KB de filas), `user` (administradores con hash bcrypt), CVs (`job_opportunity_user_cv` apuntando a `/uploads/cvs/*.pdf`), postulaciones, notificaciones.
- `db_blaboral (6).sql` es idéntico (mismo hash SHA-256).
- **No debe importarse tal cual en una institución nueva** (contaminación de datos entre instituciones + fuga de datos personales).

**Solución adoptada:** generar `base_instalacion.sql` (ver §3) que conserva **estructura + solo datos de referencia**.

### 2.3 Migraciones registradas incompletas en el dump

El dump registra 31 migraciones, pero **faltan** los registros de:
- `0001_01_01_000001_create_cache_table`
- `0001_01_01_000002_create_jobs_table`

(sin esos registros, `migrate` intentaría recrear `cache`/`jobs` que ya existen → **error**).

**Solución adoptada:** el SQL base añade esos 2 registros (ids 32–33, batch 99).

Además, el dump registra `0001_01_01_000000_create_users_table` pero **la tabla `users` no existe** en el dump (la app usa la tabla `user` singular — `User::$table = 'user'`). Inofensivo: la app no usa `users`.

### 2.4 Datos de referencia vs. datos institucionales

| Conservar en SQL base (referencia) | Excluir (datos de la otra institución) |
|---|---|
| `rol` (5 roles) | `person`, `user` |
| `menu` (6), `option`, `rol_option` (17 permisos) | `job_opportunity_company`, `job_opportunity_offer`, `job_opportunity_applications` |
| `system_configuration` (con marca reseteada) | `job_opportunity_user_cv`, `job_opportunity_offer_state_detail` |
| `job_opportunity_contract_types`, `_modalities`, `_offer_state`, `_offer_category`, `_work_schedules` | `saved_offers`, `user_notifications`, `password_reset_tokens`, `sessions`, `study_programs` |
| `migrations` (+2 registros añadidos) | `file`, `link` (vacías en origen, sin INSERT) |

- `system_configuration`: se resetea `application_name` a marcador y `logo/favicon/banner` a `NULL` (las vistas usan `?? fallback`, verificado en `landing.blade.php` y `login.blade.php`).
- **Seeders:** `DatabaseSeeder` → `DemoLandingSeeder` (491 líneas) inserta empresas y ofertas de prueba. **No se ejecuta** en instalaciones nuevas (el prompt prohíbe seeders destructivos sin revisión).

### 2.5 Archivos persistentes

- `public/uploads/` está en `.gitignore` pero **contiene CVs y logos versionados** (4 PDFs de CV + logos, ~7 MB). Las vistas sirven `/uploads/...` desde `public/`.
- `storage/app/` vacío en el repo (solo `.gitignore`); discos: `local` → `storage/app/private`, `public` → `storage/app/public`.
- **Rutas persistentes por institución:** `.env`, `public/uploads/`, `storage/` (entero), `bootstrap/cache` es regenerable pero conviene compartirlo para sesiones/permisos estables → se mantiene en release con permisos propios.

### 2.6 `.env` y claves

- `.env.example`: `APP_ENV=local`, `APP_DEBUG=false`, `DB_CONNECTION=sqlite`, drivers `database` para sesión/cola/cache, `MAIL_MAILER=log`, integración externa `CONSULTAS_PERU_BASE_URL/TOKEN` (opcional, en `config/services.php`).
- La guía de producción del README (Pasos 1–16, líneas 269–526) es el procedimiento canónico manual:
  1. apt update + curl/wget/git/unzip, 2. PHP 8.2 + fpm + extensiones, 3. MySQL, 4. Node, 5. Composer, 6. Redis (opcional), 7. clone, 8. `.env` + `key:generate`, 9. `composer install --no-dev` + `npm run build`, 10. `migrate --force` + `storage:link` + `config:cache`, 11. Nginx (config modelo con `fastcgi_pass unix:/var/run/php/php8.2-fpm.sock`), 12. certbot, 13. supervisor worker, 14. cron `schedule:run`, 15. verificación con `curl -I`.
- **`APP_KEY` no debe regenerarse jamás en actualizaciones** (prompt §9).

### 2.7 Consideraciones de plataforma Ubuntu

- `apt install php8.2` solo funciona en 22.04 con PPA (22.04 trae PHP 8.1). En **24.04 el PHP por defecto es 8.3** (compatible con `^8.2`, sin PPA).
- **Decisión:** Ubuntu 24.04 → `php8.3` de repositorios oficiales; Ubuntu 22.04 → PPA `ondrej/php` con `php8.2`. 22.04/24.04 son los objetivos (prompt §7 PASO 3).
- `comandosvps.md` describe un despliegue **cPanel/Apache** antiguo (`/scripts/restartsrv_*`) — **no aplica** al objetivo Ubuntu+Nginx del prompt; se usa solo como referencia de operaciones (backup de `.env`, `git pull`, composer/npm, permisos `775 storage`, verificación con `curl -I`).

### 2.8 Rutas de validación post-despliegue

- `GET /` → landing (pública). `GET /login` → formulario (pública).
- `GET /admin/dashboard` → requiere `auth` + `role:1` (redirige a `/login` sin sesión).
- Validación real = comprobar en `/` y `/login` contenido esperado (nombre de app, formulario), no solo HTTP 200; más `APP_DEBUG=false`, manifiesto Vite (`public/build/manifest.json`), logs sin errores críticos, servicios `active`.

### 2.9 Otros

- No existe `routes/api.php`; solo `web.php` (122 líneas) + `console.php`.
- Colas: `QUEUE_CONNECTION=database` por defecto (Redis **opcional**; el orquestador lo instala solo si el usuario lo activa).
- Tareas programadas: el repo define rutas de consola en `console.php` (cron `schedule:run` recomendado por README).
- No se detectaron paneles de hosting en el propio repo; el diagnóstico del VPS los detectará en el servidor remoto.

---

## 3. Artefacto generado: `base_instalacion.sql`

Generado por `scripts/gen-base-sql.ps1` desde `bolsalaboralv2-audit/db_blaboral.sql`:

1. Elimina los INSERT de las 12 tablas con datos de la otra institución (§2.4).
2. Resetea marca en `system_configuration` (`application_name` → `BOLSALABORAL`, `logo/favicon/banner` → `NULL`).
3. Añade los 2 registros de migración faltantes (ids 32–33).
4. Conserva intactos `CREATE TABLE`, claves, índices, `AUTO_INCREMENT` y claves foráneas.

Se incluye como recurso del orquestador (`src/main/resources/db/base_instalacion.sql`) y es regenerable/auditable con el script.

## 4. Resumen de decisiones derivadas de la auditoría

| # | Decisión |
|---|---|
| 1 | Instalación = crear BD (utf8mb4) → importar `base_instalacion.sql` → `migrate --force` → crear admin inicial (bcrypt generado en el cliente) → actualizar `system_configuration` con el nombre real. |
| 2 | Nunca ejecutar `db:seed` automáticamente. |
| 3 | Nunca importar el dump completo con datos personales. |
| 4 | PHP: 24.04→php8.3 oficial; 22.04→PPA ondrej php8.2. |
| 5 | Node 22 LTS desde nodejs.org con verificación SHA-256 (sin `curl \| bash`). |
| 6 | Composer vía `apt` (repositorios oficiales); respaldo: instalador oficial con verificación de hash. |
| 7 | Drivers por defecto `database` (sesión/cola/cache) → sin Redis obligatorio; Redis opcional en el asistente. |
| 8 | Layout por institución: `/var/www/bolsadeploy/<slug>/{releases,shared,backups}` + `current` → release activo (cambio atómico con `ln -sfn`). |
| 9 | Persistentes en `shared/`: `.env`, `storage/`, `public/uploads/`. |
| 10 | Validación final real: contenido HTML de `/` y `/login`, manifiesto Vite, `APP_DEBUG=false`, servicios, logs. |
