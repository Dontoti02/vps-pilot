# BolsaDeploy Manager — Manual de usuario

Aplicación de escritorio para Windows que instala, actualiza, respalda y monitorea
el sistema **bolsalaboralv2** en servidores VPS Ubuntu por SSH, sin intervención
manual en el servidor.

- **Requisitos de la máquina:** Windows 10/11 (64 bits). No requiere Java instalado
  si se usa la distribución portátil o el instalador (incluyen su propio runtime).
- **Requisitos del servidor:** Ubuntu 22.04/24.04 LTS accesible por SSH (puerto 22 o
  el que usted indique), con usuario que pueda ejecutar `sudo`.
- **Red:** la máquina debe poder alcanzar el VPS por SSH y el VPS debe poder clonar
  `https://github.com/Dontoti02/bolsalaboralv2`.

---

## 1. Primer arranque

1. Ejecute `BolsaDeployManager.exe` (instalador) o el acceso directo creado, o bien
   abra la carpeta portátil y ejecute `BolsaDeployManager\BolsaDeployManager.exe`.
2. En el primer arranque se crea la base de datos local y el almacén de secretos en:
   - `%APPDATA%\BolsaDeployManager\bolsadeploy.sqlite` (VPS, instituciones, ajustes,
     historial).
   - `%APPDATA%\BolsaDeployManager\credenciales.properties` (contraseñas y claves,
     cifradas con **DPAPI** de Windows: solo su usuario de Windows puede leerlas).
   - `%APPDATA%\BolsaDeployManager\known_hosts` (huellas de los servidores).
3. Aparece la pantalla **Dashboard** con el asistento de estado. No hay nada
   configurado todavía: registre primero un VPS.

> La aplicación no abre una consola negra permanente ni requiere comandos manuales.

---

## 2. Registrar un servidor VPS

1. Vaya a **Servidores VPS** → **Nuevo servidor**.
2. Complete:
   - **Nombre** descriptivo (p. ej. `VPS Buenos Aires`).
   - **Host**: IP o dominio del servidor.
   - **Puerto**: 22 (por defecto).
   - **Usuario**: `root` o un usuario con capacidad `sudo`.
   - **Tipo de autenticación**: *Contraseña* o *Clave privada SSH*.
   - **Contraseña** / **clave privada**: se guarda cifrada; no se muestra jamás.
3. **Guardar**.
4. Pulse **Probar conexión**: la aplicación se conecta de verdad y muestra
   `uname -a` y el hostname del servidor. Si falla, verá el motivo real
   (credencial incorrecta, puerto cerrado, timeout…).

### Huellas de seguridad (host keys)

Al conectar por primera vez a un servidor aparece un diálogo con la **huella SHA256**
de su clave pública. Confirme que coincide con la que le informó su proveedor de VPS
(p. ej. en el panel del proveedor o ejecutando `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`
en el servidor) y acepte.

- Si la clave **cambia** en el futuro, el diálogo avisa en rojo que es un **CAMBIO DE
  CLAVE** y exige confirmación explícita. Esto protege frente a ataques
  man-in-the-middle: **no lo acepte a ciegas**.

---

## 3. Registrar una institución

Una *institución* es una instalación independiente de la bolsa laboral (puede haber
varias en el mismo VPS o en VPS distintos).

1. Vaya a **Instituciones** → **Nueva institución**.
2. Complete nombre, siglas, región, **dominio** (validado), email de contacto y el
   **VPS** donde se instalará.
3. El **slug** se genera automáticamente del nombre (identificador de rutas y
   carpetas: `/var/www/bolsadeploy/<slug>`). Puede editarlo si lo desea.
4. El **nombre de base de datos** y **usuario de base de datos** se autocompletan
   (`bolsa_<slug>` / `bolsa_<slug>_user`).
5. Opcional: configure SMTP (host, puerto, usuario, remitente y contraseña) para que
   la aplicación envíe correos. Si no se configura, el sistema funciona y los correos
   quedan registrados en el log.
6. Marque **HTTPS** si desea certificado Let's Encrypt (requiere que el DNS del
   dominio ya apunte a la IP del VPS).
7. **Guardar**. Se pedirá la **contraseña de la base de datos** si aún no existe
   (se guarda cifrada como `inst-<id>-db`).

---

## 4. Instalar la bolsa laboral (asistente)

1. Vaya a **Nuevo despliegue** y seleccione la institución (solo aparecen las que
   están *Pendientes* o en *Error*; las instaladas ofrecen reintentar/reparar).
2. **Paso 2 — Datos**: escriba la contraseña de la base de datos (botón *Generar*
   crea una fuerte), el nombre, email y contraseña del **administrador inicial**.
   Marque *Adoptar base existente* solo si la base ya contiene datos legítimos que
   desea conservar (no se ejecutarán seeders ni se regenerará la APP_KEY).
3. **Paso 3 — Opciones**: HTTPS, Redis (opcional) y, si lo desea, un **commit
   concreto** de la rama (vacío = última versión).
4. **Paso 4 — Revisión**: compruebe el resumen y pulse **Ejecutar instalación**.

Durante la instalación verá:

- **Consola de operaciones**: salida técnica real de cada paso (en español, sin
  secretos).
- **Barra de progreso** y las 11 etapas con su estado:
  1. Diagnóstico · 2. Dependencias · 3. Base de datos · 4. Dominio/Nginx ·
  5. Código · 6. Entorno Laravel · 7. Dependencias de la app · 8. Migraciones ·
  9. Servicios (systemd, Supervisor, cron) · 10. SSL · 11. Validación final.

**Cancelación:** el botón *Cancelar* detiene el proceso **al terminar la etapa
actual** (nunca deja el servidor a medias dentro de un comando). El progreso queda
guardado: al reintentar, las etapas ya cumplidas se omiten automáticamente
(checkpoint).

**Al finalizar** se muestra el **reporte**: URL, commit desplegado, servicios,
estado SSL, resultado de cada etapa, errores y advertencias. El resultado siempre
refleja lo que realmente pasó en el servidor.

### Si el DNS aún no apunta

La etapa SSL se marca **omitida** con el motivo ("DNS pendiente") y el sitio queda
funcionando por HTTP. Cuando el DNS esté listo, vuelva a ejecutar la instalación:
las etapas ya cumplidas se omiten y solo se completará la pendiente.

### Validación final (qué se comprueba de verdad)

- `GET /` responde 200 y el HTML contiene el título real "Oportunidades Laborales"
  (no basta con el código 200).
- `GET /login` muestra el formulario real con campo de contraseña.
- Sin errores de aplicación (Whoops/SQLSTATE/Fatal error) en la página.
- `APP_DEBUG=false` en el `.env` de producción.
- `artisan`, `public/build/manifest.json` y `vendor/autoload.php` presentes.
- `php artisan migrate:status` correcto.
- Servicios nginx, PHP-FPM y MySQL activos.

---

## 5. Comprobar el estado (Monitoreo)

1. Vaya a **Monitoreo**, elija la institución y pulse **Diagnosticar**.
2. Obtendrá una tabla de hallazgos con valores reales leídos del servidor:
   carga, memoria, disco, servicios, configuración de Nginx, socket de PHP-FPM,
   MySQL, sitio real (HTTP + contenido), worker de colas, cron, días de vigencia
   del SSL, renovación automática, errores recientes en el log, antigüedad del
   último respaldo, release activa y modo de producción.
3. **Comprobar web** realiza la comprobación desde su PC: código HTTP, latencia,
   contenido y días restantes del certificado.

Colores: ✓ verde = correcto, ! ámbar = advertencia, ✗ rojo = error, ? gris = no
evaluado.

---

## 6. Actualizar a una nueva versión

1. Vaya a **Actualizaciones**, seleccione la institución instalada y pulse
   **Consultar cambios**. La aplicación consulta GitHub y muestra:
   - Commits pendientes con sus mensajes reales.
   - Archivos modificados y **migraciones pendientes**.
   - **Riesgo detectado** (migraciones con DROP/TRUNCATE/DELETE u operaciones
     destructivas) y archivos sensibles tocados (`.env.example`, `composer.json`,
     seeders, `config/`…).
2. Si hay riesgo, debe marcar la casilla *"He revisado el riesgo y apruebo esta
     actualización"* para poder continuar.
3. Pulse **Actualizar**. La secuencia es:
   - Respaldo **INTEGRAL previo** verificado (obligatorio por defecto).
   - Clonado exacto del commit objetivo en `releases/<sha>`.
   - `composer install` + build de frontend sobre la release nueva (el sitio sigue
     sirviendo la versión anterior).
   - `php artisan migrate --force`.
   - Conmutación **atómica** de `current` (`ln -sfn`).
   - Validación real por HTTP + contenido.
   - **Rollback automático** a la versión anterior si la validación falla.
   - Limpieza de releases antiguas (se conservan 3).
4. **Actualizar todas las aptas** aplica el mismo flujo a varias instituciones con
   la concurrencia configurada (por defecto 2). Las que requieren aprobación
   explícita se omiten con aviso; un fallo en una no detiene a las demás.

**Nunca** se ejecuta `migrate:fresh` ni `db:wipe`, **nunca** se regenera la
`APP_KEY`, **nunca** se reescribe su `.env` y **nunca** se ejecutan seeders: sus
usuarios, empresas, ofertas, postulaciones, CV y archivos se conservan.

---

## 7. Copias de seguridad (Copias)

Con la institución seleccionada en **Copias**:

| Tipo | Contenido |
|------|-----------|
| **BD** | `mysqldump --single-transaction` comprimido con gzip. |
| **ARCHIVOS** | `storage/` y `public/uploads/` (CVs y logos) en tar.gz. |
| **CONFIG** | `.env` (⚠ contiene secretos; en el VPS queda con permiso 600). |
| **INTEGRAL** | BD + archivos + configuración en un solo tar.gz. |

- Todo respaldo se **verifica de verdad** (integridad gzip/tar y presencia de
  `CREATE TABLE` en el volcado). Si no pasa, se registra como **FALLIDA** y el
  archivo se elimina: nunca verá un respaldo "OK" que no lo esté.
- **Descargar a PC** lo guarda en la carpeta de respaldos configurada.
- **Restaurar** es un asistente de dos pasos: advertencia clara + casilla
  *"Entiendo que esto reemplaza datos actuales"*. Antes de restaurar se crea
  siempre un **respaldo de seguridad** del estado actual.
- **Purgar** conserva los N respaldos más recientes que usted indique.

---

## 8. Historial

**Historial** muestra todas las operaciones registradas (despliegues, actualizaciones,
respaldos, restauraciones, diagnósticos, mantenimiento) con su fecha, estado y
detalle técnico. Puede filtrar por tipo y estado. La salida técnica nunca contiene
contraseñas ni tokens (se enmascaran automáticamente).

---

## 9. Configuración

En **Configuración** puede cambiar:

- **Tema** (oscuro/claro, se aplica al instante).
- **Carpeta de respaldos** locales (por defecto `%APPDATA%\BolsaDeployManager\respaldos`).
- **Ruta base de instalación** en el servidor (por defecto `/var/www/bolsadeploy`).
- **Repositorio GitHub** y rama por defecto (por defecto `Dontoti02/bolsalaboralv2` / `main`).
- **Token de GitHub** (opcional; amplía el límite de consultas a la API).
- **Concurrencia de actualizaciones** (1–5).
- **Puerto SSH** y **timeout** por defecto.
- **Respaldo previo a actualizaciones** (recomendado: activado).
- **Verificar host keys** (recomendado: activado).

La sección **Seguridad** indica el mecanismo de cifrado de credenciales (DPAPI en
Windows) sin mostrar ningún valor. El botón *Abrir carpeta de datos* abre la carpeta
con la base, las credenciales y los logs.

---

## 10. Solución de problemas

| Síntoma | Qué hacer |
|---------|-----------|
| "Autenticación rechazada" | Verifique contraseña/clave en **Servidores VPS** → Editar. |
| "Puerto cerrado" / timeout | Confirme IP, puerto SSH y firewall del VPS. |
| Diálogo de host key | Compare la huella con la del proveedor. Si **cambia**, investigue antes de aceptar. |
| Instalación se detiene en una etapa | Lea el motivo en la consola; el reporte incluye la acción correctiva. Reintente: las etapas cumplidas se omiten. |
| "HTTPS pendiente: DNS pendiente" | Apunte el dominio al IP del VPS y reintente (solo se ejecutará la etapa SSL). |
| La web muestra errores tras actualizar | Se hizo rollback automático; revise el reporte y, si hace falta, restaure el respaldo previo desde **Copias**. |
| GitHub responde error de cuota | Configure un token en **Configuración**. |
| Olvidé dónde están mis datos | **Configuración** → *Abrir carpeta de datos*. |

Los logs completos de la aplicación están en `%APPDATA%\BolsaDeployManager\logs`
(y en el historial de la aplicación).
