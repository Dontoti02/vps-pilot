# PROMPT MAESTRO — DESARROLLO DE ORQUESTADOR DE DESPLIEGUES DE BOLSA LABORAL

## 1. ROL Y OBJETIVO PRINCIPAL

Actúa como un arquitecto de software sénior especializado en Java moderno, JavaFX, aplicaciones de escritorio Windows, automatización DevOps, administración de servidores Linux Ubuntu, SSH, Laravel, MySQL, seguridad informática y despliegues de producción.

Necesito que desarrolles desde cero una aplicación de escritorio profesional llamada:

**BolsaDeploy Manager**

Su objetivo es automatizar la instalación, configuración, administración, actualización y recuperación de mi sistema de bolsa laboral en múltiples servidores VPS Ubuntu.

**Repositorio oficial del sistema:**

https://github.com/Dontoti02/bolsalaboralv2.git

El programa debe ejecutarse LOCALMENTE en mi computadora Windows, mediante un ejecutable `.exe`, y conectarse por SSH a mis diferentes VPS.

No quiero instalar un orquestador central en Internet ni depender de un servidor adicional para administrar mis instalaciones.

La arquitectura debe ser:

- Aplicación Java instalada en Windows.
- Base de datos local para guardar instituciones y despliegues.
- Conexiones SSH seguras a servidores Ubuntu.
- Ejecución de procedimientos automatizados en los VPS.
- Sincronización y comprobación de versiones desde GitHub.
- Administración independiente de cada instalación.

El objetivo fundamental es que pueda desplegar la bolsa laboral de una nueva institución sin configurar manualmente Nginx, PHP, MySQL, Laravel, certificados SSL ni otros servicios.

Posteriormente, cuando publique nuevas versiones en GitHub, quiero abrir el programa, seleccionar una institución y actualizar su bolsa laboral de forma controlada.

**IMPORTANTE: no desarrolles una simulación. Necesito un producto funcional, compilable, instalable y preparado para pruebas reales en VPS Ubuntu.**

---

## 2. ANÁLISIS OBLIGATORIO DEL REPOSITORIO

Antes de programar el orquestador, clona y analiza completamente:

https://github.com/Dontoti02/bolsalaboralv2.git

Inspecciona como mínimo:

- `README.md`
- `comandosvps.md`
- `composer.json`
- `composer.lock`
- `package.json`
- `package-lock.json`
- `.env.example`
- `.gitignore`
- `config/`
- `routes/`
- `database/migrations/`
- `database/seeders/`
- `public/`
- `storage/`
- Configuración de colas, sesiones, archivos y tareas programadas.

La inspección inicial indica que el proyecto utiliza Laravel 12, PHP 8.2+, MySQL/MariaDB, Vite 7 y Tailwind CSS 4.

Estas características deben verificarse contra la versión real descargada del repositorio.

Identifica las dependencias efectivas, extensiones PHP, directorios persistentes, estructura de almacenamiento, comandos Artisan, configuración de correo, servicios externos y cualquier requisito especial.

Ten especial cuidado con:

1. `public/uploads`, porque aparece excluido del control de versiones y podría contener archivos persistentes.
2. `storage/app`, donde pueden almacenarse CV, documentos e imágenes.
3. `.env`, que contiene credenciales y configuraciones particulares de cada institución.
4. `APP_KEY`, que nunca debe regenerarse durante una actualización.
5. Archivos `.sql` incluidos en el repositorio, que no deben importarse automáticamente sin verificar su propósito y contenido.
6. Los seeders, que podrían crear, modificar o sobrescribir información.
7. El funcionamiento del registro y la administración inicial de usuarios.
8. Las integraciones externas opcionales como CONSULTAS_PERU.

El orquestador debe adaptarse a la estructura real del proyecto y no asumir que todas las aplicaciones Laravel funcionan igual.

No modifiques el repositorio original salvo que sea estrictamente necesario y se justifique técnicamente. El orquestador debe ser un proyecto independiente.

---

## 3. TECNOLOGÍAS DEL ORQUESTADOR

Desarrolla la aplicación con las siguientes tecnologías:

**Lenguaje y plataforma:**
- Java 25 LTS.
- JavaFX para la interfaz gráfica.
- Maven o Gradle para compilación y dependencias.
- Arquitectura modular y mantenible.
- Programación asíncrona para operaciones SSH.
- Código Java real, evitando desarrollar la aplicación principal en JavaScript, Python, Electron o un navegador embebido.

**Componentes técnicos:**
- SSHJ o biblioteca SSH Java moderna y mantenida.
- SFTP para transferencias seguras cuando sean necesarias.
- SQLite como base de datos local.
- JDBC y migraciones versionadas para persistencia.
- Jackson para serialización.
- SLF4J y Logback para registros.
- Java HttpClient para consultas públicas a GitHub.
- JavaFX CSS y una biblioteca moderna de componentes visuales, cuando resulte apropiado.
- JUnit 5 para pruebas.
- jpackage para generar el instalador de Windows.

Utiliza versiones estables compatibles, fijadas en los archivos de dependencias.

El resultado debe funcionar en Windows 10 y Windows 11 de 64 bits.

La distribución debe incluir el runtime de Java necesario para que el usuario final no tenga que instalar manualmente el JDK.

Entregar tanto un instalador `.exe` como una distribución portable cuando sea viable.

---

## 4. DISEÑO DE LA INTERFAZ GRÁFICA

Quiero una interfaz moderna, profesional y agradable, similar a los paneles actuales de administración de infraestructura.

No quiero la apariencia de un programa Java antiguo.

### Estilo visual

- Tema oscuro y tema claro.
- Sidebar lateral.
- Panel principal con tarjetas.
- Íconos modernos.
- Colores profesionales: azul, azul oscuro, blanco y acentos verdes.
- Bordes redondeados.
- Indicadores de estado.
- Formularios ordenados.
- Tablas con filtros y búsquedas.
- Barras de progreso reales.
- Ventanas de confirmación.
- Diseño adaptable a diferentes tamaños de pantalla.
- Transiciones discretas que no interfieran con las operaciones.

### Menú principal

Crear estos apartados:

**Dashboard**
- Resumen general.
- Total de instituciones.
- Total de VPS registrados.
- Instalaciones exitosas.
- Instalaciones con problemas.
- Actualizaciones disponibles.
- Últimos despliegues.

**Servidores VPS**
- Registrar servidor.
- Editar servidor.
- Comprobar SSH.
- Ver diagnóstico.
- Ver aplicaciones alojadas.
- Consultar servicios instalados.

**Instituciones**
- Registrar institución.
- Editar datos.
- Vincular VPS.
- Configurar dominio.
- Seleccionar versión.
- Consultar estado de instalación.
- Acceder al sitio.

**Nuevo despliegue**
- Asistente de instalación.
- Diagnóstico previo.
- Configuración.
- Ejecución.
- Validación final.

**Actualizaciones**
- Consultar nuevas versiones.
- Comparar commits.
- Seleccionar instituciones.
- Actualizar una o varias instalaciones.
- Consultar historial.

**Copias de seguridad**
- Crear respaldo.
- Ver respaldos.
- Descargar respaldo.
- Validar respaldo.
- Restaurar con confirmación.

**Monitoreo**
- Estado SSH.
- Estado HTTP/HTTPS.
- Nginx.
- PHP-FPM.
- MySQL.
- Redis y workers, si están configurados.
- Uso de disco y memoria.

**Historial y registros**
- Despliegues.
- Actualizaciones.
- Errores.
- Cambios de versión.
- Operaciones de mantenimiento.

**Configuración**
- Preferencias generales.
- Tema visual.
- Seguridad.
- Credenciales.
- Carpeta de respaldos.
- Ruta predeterminada de instalación.
- GitHub.
- Políticas de actualización.

Todo debe estar en español.

---

## 5. MÓDULO DE ADMINISTRACIÓN DE VPS

Implementar un formulario para registrar servidores Ubuntu.

Campos:

- Nombre identificador del servidor.
- Dirección IP o hostname.
- Puerto SSH, predeterminado 22.
- Usuario SSH.
- Tipo de autenticación.
- Clave privada SSH, si corresponde.
- Contraseña SSH, si se utiliza.
- Descripción.
- Grupo o categoría.
- Estado de conexión.

Permitir autenticación mediante contraseña o clave SSH.

Priorizar las claves SSH y verificar la identidad del servidor mediante su fingerprint y `known_hosts`.

En la primera conexión, presentar al usuario la huella del servidor y solicitar su aceptación explícita.

Nunca aceptar silenciosamente un cambio inesperado de host key.

### Botón COMPROBAR SSH

Cuando pulse este botón, el programa deberá:

1. Intentar conectarse al servidor.
2. Autenticar al usuario.
3. Comprobar la identidad del host.
4. Obtener distribución y versión de Ubuntu.
5. Detectar arquitectura y recursos.
6. Verificar permisos y disponibilidad de sudo.
7. Consultar espacio libre, RAM y CPU.
8. Detectar software de infraestructura instalado.
9. Mostrar los resultados.

Mensajes de ejemplo:

- SSH conectado correctamente.
- Ubuntu 24.04 detectado.
- PHP encontrado: versión correspondiente.
- Nginx detectado.
- MySQL instalado.
- Espacio disponible: suficiente.
- Permisos de administración: disponibles.

Si falla, indicar la causa real, como autenticación rechazada, puerto cerrado, timeout, host key modificada o falta de permisos.

No mostrar mensajes falsos de conexión exitosa.

---

## 6. REGISTRO DE INSTITUCIONES

Cada institución representa una instalación independiente de la bolsa laboral.

Campos:

- Nombre completo.
- Siglas.
- Código interno.
- Región.
- Dominio.
- Correo de contacto opcional.
- Servidor VPS asignado.
- Ruta de instalación.
- Nombre de base de datos.
- Usuario de base de datos.
- Rama o versión de GitHub.
- Configuración de correo.
- Estado de despliegue.
- Fecha de instalación.
- Última actualización.
- Commit actualmente desplegado.

Ejemplo:

Institución: IESTP DEMOSTRACIÓN

Dominio: `bolsa.ejemplo.edu.pe`

Servidor: VPS UBUNTU 01

Repositorio: `Dontoti02/bolsalaboralv2`

Rama: `main`

Estado: Pendiente de instalación.

El programa debe permitir administrar numerosas instituciones.

Varias pueden utilizar el mismo VPS, siempre que exista capacidad y que sus rutas, dominios, bases de datos y servicios no entren en conflicto.

Cada institución debe conservar una configuración independiente.

---

## 7. ASISTENTE DE DESPLIEGUE AUTOMÁTICO

Este es el módulo más importante.

Implementar un asistente gráfico paso a paso.

### PASO 1. Seleccionar institución

Permitir seleccionar una institución previamente registrada o crear una nueva.

### PASO 2. Seleccionar servidor

Seleccionar un VPS registrado.

Verificar automáticamente la conexión SSH.

### PASO 3. Diagnóstico del VPS

Antes de instalar, verificar:

- Ubuntu 22.04 o 24.04, principalmente.
- Compatibilidad de arquitectura.
- Recursos disponibles.
- Acceso a Internet.
- Resolución DNS.
- Puertos necesarios.
- Conflictos con dominios existentes.
- Instalaciones previas.
- Permisos sudo.
- Existencia de Nginx o Apache.
- Existencia de paneles de hosting que puedan entrar en conflicto.
- Versión y extensiones de PHP.
- Instalaciones previas de MySQL.
- Disponibilidad de paquetes.

Mostrar un reporte con estados:

CORRECTO, ADVERTENCIA, ERROR o NO EVALUADO.

Si el servidor ya tiene aplicaciones funcionando, NO modificar sus configuraciones sin validar previamente el impacto.

### PASO 4. Instalación automática de dependencias

El orquestador deberá detectar componentes existentes e instalar únicamente los que falten o deban actualizarse por requisitos de compatibilidad.

Componentes posibles:

**Sistema operativo**
- Herramientas de administración.
- Git.
- curl.
- wget.
- unzip.
- Certificados CA.
- Herramientas de diagnóstico.

**Servidor web**
- Nginx.
- PHP-FPM.
- Configuración de virtual hosts.

**PHP**
- Versión compatible con el proyecto.
- PHP CLI.
- PHP-FPM.
- Extensiones necesarias identificadas en Composer.
- pdo_mysql, mbstring, XML, cURL, ZIP, GD, BCMath, INTL, fileinfo y otras dependencias verificadas.

**Base de datos**
- MySQL o MariaDB.
- Base independiente por institución.
- Usuario dedicado.
- Permisos mínimos.
- Contraseña segura.

**Node.js**
- Versión compatible con Vite 7.
- Usar una versión LTS compatible, como Node.js 22.12 o superior dentro de una línea soportada.
- npm.

**Composer**
- Composer 2.
- Verificar compatibilidad con PHP.

**Servicios adicionales**
- Redis, cuando se configure.
- Supervisor, cuando se utilicen workers.
- Certbot y Let's Encrypt.
- Tareas programadas Laravel.

No instalar paquetes innecesarios ni actualizar indiscriminadamente todo el sistema.

No ejecutar scripts descargados directamente de Internet sin verificar su fuente y seguridad.

Las instalaciones deben ser idempotentes: si se ejecuta el diagnóstico nuevamente no debe duplicar servicios, configuraciones ni recursos.

### PASO 5. Configuración de dominio

Solicitar:

- Dominio principal.
- Uso de www, si corresponde.
- Correo para Let's Encrypt.
- Activación de HTTPS.

Verificar los registros DNS A y AAAA pertinentes antes de solicitar certificados.

Configurar Nginx para servir Laravel desde su directorio `public`.

Configurar el socket PHP-FPM correspondiente a la versión realmente instalada.

Validar Nginx antes de recargarlo.

No eliminar configuraciones de otros sitios del VPS.

### PASO 6. Configuración de base de datos

Crear una base de datos exclusiva de la institución.

Crear un usuario dedicado.

Asignar únicamente los privilegios necesarios sobre su base.

Utilizar codificación utf8mb4.

No utilizar el usuario root de MySQL como usuario de aplicación.

Si la base de datos ya existe, detener la creación y ofrecer opciones seguras de adoptar, reutilizar o configurar otra.

No sobrescribir bases existentes automáticamente.

### PASO 7. Descargar código desde GitHub

Repositorio oficial:

https://github.com/Dontoti02/bolsalaboralv2.git

El sistema debe poder instalar desde:

- Rama main.
- Tag disponible.
- Commit específico.

Guardar exactamente el SHA del commit utilizado.

Utilizar Git de forma controlada para permitir actualizaciones futuras.

No utilizar un archivo ZIP aislado que impida identificar posteriormente la versión desplegada.

### PASO 8. Configurar Laravel

Preparar el archivo `.env` por institución.

Campos configurables:

- APP_NAME.
- APP_ENV=production.
- APP_DEBUG=false.
- APP_URL.
- APP_KEY.
- DB_CONNECTION.
- DB_HOST.
- DB_PORT.
- DB_DATABASE.
- DB_USERNAME.
- DB_PASSWORD.
- SESSION_DRIVER.
- QUEUE_CONNECTION.
- CACHE_STORE.
- FILESYSTEM_DISK.
- Configuración SMTP.
- Parámetros externos identificados en el repositorio.

Generar APP_KEY únicamente en una instalación nueva y solamente cuando no exista una clave previa válida.

Conservar las credenciales y configuraciones de cada institución después de actualizar.

Validar la sintaxis del archivo `.env`.

### PASO 9. Dependencias de aplicación

Ejecutar en el contexto correspondiente:

`composer install --no-dev --prefer-dist --no-interaction --optimize-autoloader`

`npm ci`

`npm run build`

Verificar el resultado real de cada operación.

Utilizar los archivos lock del proyecto.

Si falla una dependencia, detener el proceso, mostrar el error y permitir reintentar la etapa.

Comprobar la generación de los assets Vite y su manifiesto.

### PASO 10. Base de datos y archivos persistentes

Ejecutar migraciones Laravel solamente bajo condiciones seguras.

En instalaciones nuevas, analizar si se requieren seeders.

No importar automáticamente archivos SQL de muestra ni ejecutar seeders destructivos.

Crear los enlaces de almacenamiento necesarios.

Preservar correctamente:

- Archivos subidos.
- Fotografías.
- Logos.
- Documentos.
- Currículums vitae.
- Archivos de `storage/app`.
- Directorio `public/uploads`, cuando corresponda.
- Cualquier otra ruta persistente detectada.

Configurar permisos apropiados para el usuario de despliegue y el servicio web.

No utilizar permisos 777.

### PASO 11. Servicios de producción

Configurar Nginx y PHP-FPM.

Cuando sea necesario:

- Configurar worker de Laravel con Supervisor.
- Configurar cron del scheduler.
- Configurar Redis.
- Configurar reinicios controlados de workers.

Cada institución debe tener identificadores de servicios y configuraciones independientes.

No crear cron duplicados.

### PASO 12. SSL

Verificar previamente:

- Resolución del dominio.
- Disponibilidad de puertos HTTP/HTTPS.
- Configuración válida de Nginx.

Solicitar certificados Let's Encrypt cuando sea posible.

Verificar HTTPS y renovación automática.

Si DNS todavía no apunta al VPS, no marcar SSL como instalado; permitir completar esa etapa posteriormente.

### PASO 13. Validación final

Comprobar:

- Dominio accesible.
- HTTPS válido.
- Respuesta HTTP esperada.
- Página principal operativa.
- Página de login.
- Conexión de Laravel con la base de datos.
- `APP_DEBUG=false`.
- Archivo de configuración correcto.
- Assets Vite cargados.
- Directorios de almacenamiento accesibles.
- Servicios requeridos activos.
- Ausencia de errores críticos en logs.

No considerar un HTTP 200 como prueba suficiente de funcionamiento.

No declarar el despliegue exitoso hasta completar las verificaciones obligatorias.

### PASO 14. Reporte

Mostrar:

- Institución.
- IP y servidor.
- Dominio.
- Fecha.
- Versión desplegada.
- Commit.
- Servicios utilizados.
- Estado de SSL.
- Resultado de cada etapa.
- Errores o advertencias.
- Enlace al sitio.

Permitir exportar el informe a PDF o HTML.

---

## 8. MÓDULO DE ACTUALIZACIONES DESDE GITHUB

ESTA FUNCIÓN ES FUNDAMENTAL.

Quiero poder actualizar el código original de mi bolsa laboral en GitHub y posteriormente actualizar las instalaciones desde el programa Windows.

### Ejemplo de uso

Tengo 15 institutos con bolsas laborales instaladas.

Publico cambios nuevos en el repositorio.

Abro BolsaDeploy Manager.

Ingreso al apartado ACTUALIZACIONES.

El programa consulta GitHub y detecta qué instalaciones utilizan versiones antiguas.

Selecciono uno o varios institutos.

Reviso la versión nueva y sus cambios.

Presiono ACTUALIZAR.

El programa realiza una actualización segura de cada instalación seleccionada.

### Funcionalidades obligatorias

**Comprobar actualizaciones**

Consultar GitHub y comparar:

- Commit instalado.
- Commit disponible.
- Rama.
- Tag.
- Fecha de versión.
- Cambios pendientes.

No asumir que una actualización existe solamente porque la fecha del repositorio cambió.

**Comparación de versiones**

Mostrar:

Versión instalada: commit anterior.

Versión disponible: commit nuevo.

Estado: actualización disponible.

Permitir consultar cambios y detectar archivos potencialmente sensibles.

**Actualización individual**

Actualizar una sola institución.

**Actualización múltiple**

Seleccionar varias instituciones mediante casillas y ejecutar actualizaciones controladas con límite de concurrencia.

Mostrar progreso individual.

El fallo de una instalación no debe provocar cambios descontrolados en las demás.

**Actualización por versión**

Permitir seleccionar un tag o commit específico.

No limitarse a descargar automáticamente la última versión de main.

---

## 9. PROCEDIMIENTO SEGURO DE ACTUALIZACIÓN

El procedimiento debe seguir esta secuencia:

1. Conectar al VPS.
2. Verificar identidad SSH.
3. Validar institución, dominio y ruta.
4. Confirmar qué commit está instalado.
5. Consultar el commit solicitado.
6. Verificar cambios locales, espacio y recursos.
7. Evaluar riesgos de migraciones.
8. Crear respaldo de base de datos.
9. Crear respaldo de archivos persistentes y configuración.
10. Validar que los respaldos sean utilizables.
11. Preparar el código nuevo en un directorio de release independiente.
12. Instalar dependencias con Composer.
13. Compilar assets con npm.
14. Ejecutar pruebas y validaciones preliminares.
15. Coordinar workers y modo mantenimiento cuando sea necesario.
16. Ejecutar migraciones aprobadas.
17. Activar el nuevo release mediante cambio atómico de enlace simbólico.
18. Recargar servicios requeridos.
19. Comprobar funcionamiento.
20. Registrar la nueva versión únicamente si la validación fue satisfactoria.
21. Salir del modo mantenimiento.
22. Conservar el release anterior para recuperación.

### Restricciones críticas

NUNCA:

- Ejecutar `migrate:fresh` automáticamente.
- Ejecutar `db:wipe`.
- Eliminar datos para solucionar errores de migración.
- Regenerar APP_KEY en actualizaciones.
- Reemplazar el `.env` existente por el del repositorio.
- Borrar CV, documentos o imágenes.
- Sobrescribir la base de datos con archivos SQL de GitHub.
- Ejecutar seeders sin revisión.
- Aplicar `git reset --hard` sobre modificaciones locales sin autorización.
- Ejecutar actualizaciones sobre rutas no verificadas.
- Sustituir una instalación operativa con un release fallido.

Si se detectan migraciones potencialmente destructivas, suspender y solicitar aprobación explícita, mostrando el riesgo.

La restauración del código anterior no debe presentarse como restauración automática de la estructura anterior de la base de datos.

---

## 10. ARQUITECTURA DE RELEASES Y PERSISTENCIA

Diseñar una estructura de despliegue similar a:

`/var/www/bolsadeploy/<institucion>/`

Con subdirectorios conceptuales:

- `releases/`
- `shared/`
- `backups/`
- `current`

Dentro de `shared/` conservar el entorno y los datos permanentes.

El enlace `current` señalará la versión activa.

Cada release se identificará por un código o SHA verificable.

El cambio de versión se realizará mediante una operación atómica cuando el sistema de archivos lo permita.

El diseño debe tratar correctamente las carpetas persistentes y sus enlaces simbólicos.

No asumir que Laravel almacena todo exclusivamente en `storage/`; verificar también directorios como `public/uploads`.

Evitar que una nueva versión cree carpetas vacías que oculten documentos existentes.

Los archivos permanentes no pueden quedar dentro de directorios que se eliminan al limpiar versiones antiguas.

---

## 11. RESPALDOS Y RECUPERACIÓN

Crear un módulo de respaldos.

Tipos:

- Base de datos.
- Archivos persistentes.
- Configuración.
- Respaldo integral.

Permitir respaldos manuales y respaldos automáticos previos a una actualización.

Cada respaldo deberá incluir:

- Institución.
- Fecha y hora.
- Commit asociado.
- Tipo.
- Tamaño.
- Ubicación.
- Resultado de verificación.
- Identificador único.

Para MySQL utilizar mecanismos de respaldo consistentes, con comprobación de salida, integridad y espacio.

Proteger credenciales durante la exportación.

Permitir descargar respaldos por SFTP a Windows.

Los respaldos deben poder almacenarse fuera del VPS.

### Restauración

Implementar un asistente que permita:

1. Seleccionar institución.
2. Seleccionar respaldo.
3. Inspeccionar contenido.
4. Comprobar integridad.
5. Crear un respaldo adicional de seguridad.
6. Mostrar el impacto de la restauración.
7. Solicitar confirmación explícita.
8. Restaurar exclusivamente los componentes autorizados.
9. Verificar funcionamiento.

La recuperación de una versión anterior del código y la restauración de una base de datos deben ser operaciones diferenciadas.

No realizar restauraciones destructivas sin aprobación.

---

## 12. MONITOREO DE INSTALACIONES

Crear una vista de monitoreo por institución.

Mostrar:

- Estado SSH.
- Estado del dominio.
- Estado HTTPS.
- Validez del certificado SSL.
- Espacio libre del VPS.
- Memoria disponible.
- Uso de CPU.
- Estado de Nginx.
- Estado de PHP-FPM.
- Estado de MySQL.
- Estado de Redis, cuando corresponda.
- Estado de Supervisor.
- Versión instalada.
- Último respaldo.
- Última actualización.

Permitir actualizar el diagnóstico manualmente.

No afirmar que existe monitoreo continuo cuando la aplicación Windows está cerrada.

El monitoreo bajo demanda debe funcionar sin agentes residentes instalados en cada VPS.

---

## 13. TERMINAL Y LOGS DE EJECUCIÓN

Incluir una consola de operaciones en la interfaz.

Durante un despliegue mostrar mensajes como:

`[OK] Conexión SSH establecida`

`[OK] Ubuntu compatible`

`[OK] PHP verificado`

`[OK] Base de datos creada`

`[OK] Código descargado`

`[OK] Dependencias instaladas`

`[OK] Assets compilados`

`[OK] Nginx validado`

`[OK] Aplicación operativa`

Cada etapa debe mostrar:

- Nombre.
- Estado.
- Hora de inicio.
- Duración.
- Mensaje.
- Progreso real cuando pueda medirse.
- Salida técnica.
- Posibilidad de reintento seguro.

Permitir:

- Ver logs en tiempo real.
- Copiar resultados.
- Exportar registros.
- Consultar operaciones anteriores.
- Ver únicamente errores.
- Cancelar operaciones de manera segura.

La cancelación no debe interrumpir arbitrariamente una operación crítica ni dejar una instalación corrupta.

Implementar checkpoints y recuperación después de interrupciones.

Nunca mostrar contraseñas, claves privadas, tokens, APP_KEY o secretos en logs.

---

## 14. SEGURIDAD DEL ORQUESTADOR

La aplicación administrará infraestructura real, por lo que la seguridad es prioritaria.

Aplicar como mínimo:

**Conexiones SSH**
- Verificación estricta de host keys.
- Soporte de claves privadas.
- Autenticación segura.
- Timeout.
- Control de reconexión.
- Gestión correcta de sesiones.

**Credenciales**
- Integración con protección de credenciales de Windows, como DPAPI o Credential Manager.
- No guardar contraseñas en SQLite en texto plano.
- No guardar claves privadas innecesariamente.
- No escribir secretos en archivos temporales inseguros.
- Permitir eliminar credenciales guardadas.

**Comandos remotos**
- Usar plantillas de operaciones verificadas.
- Validar parámetros.
- Escapar correctamente argumentos.
- Evitar inyección de comandos.
- No ejecutar entradas de usuario directamente como instrucciones de shell.
- No permitir rutas arbitrarias destructivas.
- Registrar cada operación administrativa.

**Permisos**
- Preferir usuario de despliegue dedicado.
- Utilizar sudo únicamente para tareas que lo requieran.
- Comprobar los permisos antes de iniciar.
- Evitar ejecutar Composer y npm como root.
- Restringir los permisos de los archivos de configuración.

**Base de datos**
- Usuarios independientes.
- Privilegios mínimos.
- MySQL no debe quedar expuesto públicamente por defecto.

**Protección de datos**
- Preservar información institucional.
- Proteger backups.
- No compartir credenciales entre instituciones.
- No publicar variables secretas en GitHub.

**Control de cambios**
- Confirmar acciones peligrosas.
- Registrar commit y operador.
- Proporcionar vista previa de cambios.
- Bloquear operaciones incompatibles concurrentes sobre una misma institución.

---

## 15. HISTORIAL DE DESPLIEGUES

Guardar una base de datos local con:

- VPS.
- Instituciones.
- Configuraciones.
- Rutas de instalación.
- Versiones.
- Historial de releases.
- Respaldos.
- Estado de operaciones.
- Resultados de diagnósticos.
- Errores y advertencias.

La base local debe mantenerse entre reinicios y actualizaciones del orquestador.

Permitir exportar la configuración de instituciones sin credenciales secretas y volver a importarla.

Incluir migraciones del esquema local para actualizaciones futuras de la aplicación.

---

## 16. ARQUITECTURA DEL SOFTWARE

Organiza el programa en módulos claros:

`ui` — Interfaz JavaFX.

`application` — Casos de uso y coordinación.

`domain` — Entidades y reglas del negocio.

`infrastructure.ssh` — Conexiones SSH.

`infrastructure.persistence` — SQLite.

`infrastructure.github` — Consulta de versiones.

`deployment` — Instalación inicial.

`updates` — Actualizaciones.

`backups` — Respaldos y recuperación.

`monitoring` — Diagnósticos.

`security` — Credenciales y validación.

`logging` — Auditoría y registros.

`packaging` — Distribución Windows.

Separar la lógica de despliegue de los controladores JavaFX.

Las operaciones remotas deben ejecutarse fuera del hilo gráfico.

Aplicar patrones apropiados como Strategy, Command y State cuando simplifiquen el diseño.

Implementar etapas de despliegue independientes y reutilizables.

Cada operación debe tener precondiciones, ejecución, comprobación y manejo de errores.

Evitar clases gigantes que concentren toda la lógica.

---

## 17. EXPERIENCIA DE USUARIO DEL DESPLIEGUE

El asistente debe ser fácil de utilizar incluso si el administrador no conoce todos los comandos Linux.

Ejemplo de flujo:

Abro BolsaDeploy Manager.

Pulso NUEVA INSTITUCIÓN.

Registro los datos.

Selecciono el VPS.

Ingreso dominio y configuración de base de datos.

Pulso COMPROBAR SERVIDOR.

Reviso los requisitos y cambios previstos.

Pulso INICIAR DESPLIEGUE.

El programa instala los componentes necesarios, configura el sistema, descarga mi bolsa laboral y verifica su funcionamiento.

Finalmente muestra el dominio y el reporte de instalación.

No quiero que el proceso dependa de copiar y pegar manualmente numerosos comandos.

Las intervenciones manuales deben limitarse a decisiones que requieran autorización, como proporcionar credenciales, configurar DNS externo o aprobar cambios con riesgo.

---

## 18. ACTUALIZACIONES MASIVAS

Implementar una tabla que muestre:

| Institución | VPS | Versión instalada | Nueva versión | Estado |
|---|---|---|---|---|
| IESTP A | VPS 1 | Commit A | Commit B | Pendiente |
| IESTP B | VPS 2 | Commit A | Commit B | Pendiente |
| IESTP C | VPS 3 | Commit B | Commit B | Actualizada |

Permitir seleccionar las instituciones que recibirán una actualización.

Implementar:

- Selección individual.
- Selección múltiple.
- Comprobación de compatibilidad.
- Vista previa de cambios.
- Confirmación.
- Límite de operaciones paralelas.
- Progreso separado por institución.
- Respaldo por institución.
- Reporte consolidado.

Las actualizaciones deben ser iniciadas expresamente por el usuario.

No modificar todas las instalaciones automáticamente cada vez que cambia GitHub.

---

## 19. GESTIÓN DE ERRORES

El programa debe gestionar correctamente fallos como:

- VPS inaccesible.
- Credenciales incorrectas.
- Cambio de huella SSH.
- Fallo DNS.
- Ubuntu incompatible.
- Falta de permisos sudo.
- Espacio insuficiente.
- Error de apt.
- Paquete incompatible.
- PHP incorrecto.
- Error Composer.
- Error npm.
- Error de compilación Vite.
- Base de datos no disponible.
- Error de migración.
- Conflicto de configuración Nginx.
- Fallo SSL.
- Dominio ocupado.
- Archivo `.env` inválido.
- Fallo de permisos.
- Corte de conexión SSH.
- Interrupción de Windows durante un despliegue.
- Error en workers.
- Actualización incompatible.

Para cada error:

1. Identificar la etapa.
2. Conservar los logs técnicos.
3. Explicar el problema en español.
4. Indicar la acción correctiva.
5. Permitir reintento si es seguro.
6. Preservar datos.
7. Evitar marcar como exitosa una operación incompleta.

No ocultar excepciones ni simular resultados.

---

## 20. GENERACIÓN DEL EJECUTABLE WINDOWS

Quiero un programa real de escritorio Windows.

Nombre final de la aplicación:

**BolsaDeploy Manager**

Entregar:

- Código fuente Java.
- Proyecto completo con dependencias.
- Instrucciones de compilación.
- Ejecutable de la aplicación.
- Instalador `.exe`.
- Distribución portable, si es técnicamente viable.
- Icono propio.
- Manual de usuario.
- README técnico.

Utilizar `jpackage` con runtime Java integrado.

La construcción del instalador Windows debe ejecutarse en Windows o en un entorno de compilación Windows compatible.

No presentar un simple `.jar` como si fuese el producto final solicitado.

Si se genera un instalador `.exe`, documentar que el instalador contiene el programa y su runtime, y que el ejecutable de uso se encontrará instalado.

El programa no debe abrir una consola negra permanentemente ni requerir ejecutar comandos manuales para funcionar.

---

## 21. PRUEBAS OBLIGATORIAS

Antes de considerar terminado el proyecto, ejecutar pruebas de:

### Aplicación Windows

- Apertura de interfaz.
- Persistencia local.
- Registro de VPS.
- Registro de instituciones.
- Validación de formularios.
- Navegación entre pantallas.
- Manejo de errores.
- Empaquetado.
- Apertura del ejecutable.

### SSH

- Autenticación correcta.
- Credenciales incorrectas.
- Host key desconocida.
- Host key modificada.
- Timeout.
- Pérdida de conexión.
- Reconexión.
- Ejecución y captura de salida remota.

### Despliegues

- VPS limpio.
- VPS con dependencias instaladas.
- VPS con otros sitios.
- Instalación inicial.
- Reintento idempotente.
- Errores parciales.
- DNS no configurado.
- Configuración SSL.
- Verificación de servicios.

### Actualizaciones

- Versión igual.
- Versión superior.
- Selección de commit.
- Compilación fallida.
- Migración fallida.
- Fallo después del cambio de release.
- Recuperación de código.
- Conservación de `.env`.
- Conservación de `APP_KEY`.
- Conservación de CV y archivos.
- Actualización múltiple.

### Backups

- Respaldo de base de datos.
- Respaldo de archivos.
- Verificación de integridad.
- Descarga por SFTP.
- Restauración controlada en entorno de prueba.

Crear pruebas unitarias y de integración.

Utilizar mocks para SSH y servicios externos donde corresponda.

Realizar pruebas reales sobre una VM o VPS Ubuntu de prueba antes de recomendar producción.

No afirmar que se ha validado un despliegue real si solamente se ejecutaron pruebas simuladas.

---

## 22. ENTREGABLES OBLIGATORIOS

No te limites a explicar cómo programar la aplicación.

Debes desarrollar el proyecto.

Entregar:

1. Proyecto Java completo.
2. Código JavaFX.
3. Motor SSH funcional.
4. Administración de VPS.
5. Administración de instituciones.
6. Instalación automatizada.
7. Módulo de actualizaciones.
8. Sistema de respaldos.
9. Recuperación de versiones.
10. Monitoreo básico.
11. Historial y logs.
12. Base SQLite.
13. Pruebas automatizadas.
14. Scripts de compilación.
15. Configuración jpackage.
16. Instalador Windows `.exe`.
17. Manual de uso.
18. Documentación técnica.
19. Reporte de pruebas con resultados verificables.
20. Lista de limitaciones pendientes, si las hubiera.

Si el entorno actual no permite compilar el instalador Windows, entrega el código y la configuración de construcción completos, indicando con precisión qué falta para obtener el `.exe`.

No inventar enlaces, resultados de pruebas ni archivos generados.

---

## 23. ORDEN DE IMPLEMENTACIÓN

Desarrolla el sistema progresivamente mediante fases funcionales.

**FASE A — Auditoría**
Analiza el repositorio Laravel y documenta las necesidades reales de instalación.

**FASE B — Aplicación Java**
Construye el proyecto, la base local y la interfaz gráfica.

**FASE C — SSH**
Implementa conexiones y diagnósticos reales.

**FASE D — Instalación**
Implementa el despliegue integral en Ubuntu.

**FASE E — Actualizaciones**
Implementa la detección de versiones y la actualización segura.

**FASE F — Protección**
Implementa backups, rollback de código y controles de seguridad.

**FASE G — Monitoreo**
Implementa diagnósticos y logs.

**FASE H — Empaquetado**
Compila y genera los entregables Windows.

**FASE I — Validación**
Ejecuta pruebas y corrige errores.

Cada fase debe dejar código real integrado en el proyecto.

No sustituir módulos por botones decorativos o métodos vacíos.

Si una función no está implementada, indícalo claramente y no la presentes como operativa.

---

## 24. CRITERIOS DE ACEPTACIÓN FINAL

Consideraré que el sistema funciona cuando pueda:

1. Abrir BolsaDeploy Manager desde Windows.
2. Registrar un VPS Ubuntu.
3. Conectarme correctamente por SSH.
4. Obtener un diagnóstico real.
5. Registrar una institución.
6. Instalar la bolsa laboral desde GitHub.
7. Configurar Nginx, PHP y MySQL sin hacerlo manualmente.
8. Configurar el dominio y SSL cuando DNS lo permita.
9. Abrir la bolsa laboral funcionando.
10. Registrar una segunda institución.
11. Detectar una nueva versión del repositorio.
12. Actualizar únicamente la institución seleccionada.
13. Conservar usuarios, empresas, ofertas, postulaciones, CV y archivos.
14. Realizar un respaldo.
15. Recuperar una versión del código ante un fallo.
16. Consultar logs e historial.
17. Cerrar y volver a abrir el programa sin perder la configuración.

El criterio principal es que el sistema sea reutilizable, seguro y confiable para futuras instituciones.

---

## 25. INSTRUCCIÓN FINAL

Empieza inspeccionando el repositorio real.

Después presenta la arquitectura técnica y la estructura inicial del proyecto.

A continuación IMPLEMENTA las fases anteriores, creando archivos reales y ejecutando las pruebas disponibles.

Toma decisiones técnicas razonables sin detener el desarrollo por detalles menores.

Prioriza primero un flujo completo y verificable:

**Registrar VPS → Conectar SSH → Diagnosticar → Instalar bolsa laboral → Validar → Detectar nueva versión → Actualizar → Verificar datos.**

Luego completa los módulos complementarios.

No hagas una aplicación demostrativa.

No quiero solamente diagramas, propuestas o pseudocódigo.

**QUIERO EL CÓDIGO FUENTE DE UN ORQUESTADOR REAL EN JAVA MODERNO, CON INTERFAZ PROFESIONAL Y DISTRIBUCIÓN EJECUTABLE PARA WINDOWS, CAPAZ DE ADMINISTRAR DESPLIEGUES Y ACTUALIZACIONES DE MI BOLSA LABORAL EN MÚLTIPLES VPS UBUNTU.**
