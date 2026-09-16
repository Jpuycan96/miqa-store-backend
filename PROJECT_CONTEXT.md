# MIQA Store Backend — contexto de proyecto

## Eliminación administrativa de materiales — 16 de septiembre de 2026

- `product_materials` es una tabla de opciones pertenecientes a un único producto mediante `product_id NOT NULL`; no es un catálogo global y ninguna otra tabla la referencia. Se añadió `DELETE /api/admin/products/{productId}/materials/{materialId}` con la autenticación Bearer administrativa existente. Comprueba primero el producto, exige que el material pertenezca a ese producto, elimina físicamente solo esa fila y devuelve 204. Producto/material inexistente o asociación incorrecta devuelven el 404 administrativo existente.
- La eliminación es física porque el material no tiene referencias ni historial dependiente y el objetivo es retirar opciones erróneas; la baja reversible mediante `active` se conserva. No se modificó ningún dato existente, incluido el registro `"."`, ni se creó migración Flyway.
- Creación y edición de materiales normalizan el nombre con `trim`, rechazan null/vacío/solo espacios mediante Bean Validation y ahora exigen al menos una letra o número Unicode. Nombres únicamente de puntuación como `.`, `,`, `-` y `_` devuelven 400; se admiten nombres comerciales con paréntesis, medidas, acentos, `+` y guiones.
- Se agregaron pruebas HTTP para autenticación, 204, producto/material inexistentes, ownership, permanencia de productos/materiales ajenos y validación/normalización de nombres. No se ejecutaron porque el perfil test fija `127.0.0.1:55432/miqa_store_test_db`, pero ese PostgreSQL no estaba escuchando y no había credenciales TEST cargadas; nunca se sustituyó por `miqa_store_db` ni por el túnel de producción.
- Java 21 y `mvn -DskipTests package` correctos, incluyendo compilación de fuentes y tests; JAR generado. El `mvnw.cmd` local falló antes de iniciar Maven por un problema del script wrapper al evaluar `.m2`, por lo que se usó la distribución Maven 3.9.9 ya descargada por el mismo wrapper. `git diff --check` correcto. Sin commit, push ni deploy.

## Cabeceras de categoría administrables — 15 de septiembre de 2026

- V5 agrega `catalog_headline` (200) y `catalog_description` (500) opcionales a `categories`, con restricciones de texto plano, e inicializa las seis categorías por slug. La entidad y los DTO públicos/admin exponen `catalogHeadline` y `catalogDescription`; el guardado administrativo normaliza espacios y valores vacíos a null.
- Los endpoints existentes de categorías se extendieron sin crear recursos paralelos. Se añadieron pruebas para seed/serialización pública, actualización, trim, límites y rechazo de HTML. Compilación limpia y package sin tests correctos con Java 21. Las suites HTTP/Flyway no pudieron arrancar porque PostgreSQL de tests no escuchaba en 127.0.0.1:55432 y el helper no encontró binarios PostgreSQL instalados; no se usó ninguna otra base.

## Inicio local con DB DEV compartida — 15 de septiembre de 2026

`scripts/Start-Dev.ps1` levanta el backend local contra `miqa_store_dev_db` mediante un túnel SSH que debe estar abierto previamente en `127.0.0.1:5433`. El helper comprueba el puerto antes de solicitar credenciales; si no está disponible, se detiene e indica el comando SSH que debe ejecutarse en otra terminal. No abre el túnel automáticamente ni se conecta directamente al puerto PostgreSQL del VPS.

Desde cualquier PowerShell puede invocarse por su ruta; el script resuelve y cambia a la raíz del proyecto antes de ejecutar Maven:

```powershell
ssh -p 2222 -N -L 5433:localhost:5432 -o ServerAliveInterval=60 root@64.176.22.247
# En otra terminal:
& 'D:\MIQA-STORE\miqa-store-backend\scripts\Start-Dev.ps1'
```

El helper configura solo el entorno del proceso de PowerShell para usar `127.0.0.1:5433`, el rol `miqa_store_dev`, perfil `local`, CORS `http://localhost:4200`, media en `.local/media` y base `http://localhost:8081/media`. Solicita `DB_PASSWORD` de forma oculta y genera un `ADMIN_JWT_SECRET` aleatorio de 32 bytes para cada ejecución; ninguno se imprime ni se guarda y ambos se eliminan del entorno al terminar Spring. `.local/` ya está excluido de Git. El flujo no cambia los helpers de PostgreSQL local, archivos `application*.properties`, Flyway ni producción.

Validación local del helper: análisis sintáctico de PowerShell correcto, configuración/limpieza esperadas presentes y `git diff --check` correcto. No se inició Spring ni se introdujeron credenciales durante la validación.

## Base DEV compartida mediante túnel SSH — 15 de septiembre de 2026

`LocalDatabaseGuard` permite, con el perfil de desarrollo, `miqa_store_dev_db` además de `miqa_store_db` y `miqa_store_test_db`. El desarrollo compartido puede alcanzarse mediante un túnel SSH: el JDBC del backend sigue usando `localhost` o `127.0.0.1` porque el túnel termina localmente. El perfil `prod` continúa restringido exclusivamente a `miqa_store_db` y rechaza `miqa_store_dev_db`; `miqa_store_test_db` permanece reservado para tests. No se modificaron `application-prod.properties`, Flyway ni las migraciones.

## Upload físico de imágenes — 14 de septiembre de 2026

Trabajo LOCAL, sin commit, push, deploy ni acceso a producción. Al inicio ambos repositorios estaban limpios. Según el propietario, API https://api-store.solucionesmicaela.com y Nginx/media ya funcionan en producción; esas confirmaciones reemplazan los pendientes históricos de despliegue de las secciones inferiores. Esta nueva implementación todavía NO se ha desplegado.

API administrativa nueva, protegida por el Bearer existente:
- POST /api/admin/products/{pid}/images/upload, multipart/form-data: file obligatorio; altText opcional (hasta 300 caracteres), displayOrder opcional (entero no negativo), primaryImage opcional (false). Devuelve ImageView y HTTP 201.
- POST /api/admin/products/{pid}/images/{id}/remove: HTTP 204, baja lógica, sin DELETE físico del registro. No endpoint de reactivación.
- GET de imágenes y DTOs públicos/admin solo incluyen activas, ordenadas por displayOrder/id. POST manual y PUT de metadata existentes se conservan; el POST manual también respeta el límite de tres. No permite reemplazar la URL de un upload administrado: quitar y volver a subir.

Máximo tres imágenes activas por producto, incluyendo legacy; bloqueo PESSIMISTIC_WRITE del producto compartido entre creación manual, upload, principal y baja. Prueba de dos uploads simultáneos demuestra 201/409 al competir por la tercera plaza. Primera imagen sin principal se convierte en principal; primaryImage=true desmarca la anterior. Quitar la principal promueve la primera restante por displayOrder/id. Sin orden explícito, usa máximo existente + 1, con protección de overflow.

Formatos: image/jpeg (.jpg/.jpeg), image/png (.png), image/webp (.webp), hasta 5 MiB (5 MB en UI). Se comprueba MIME/extensión y firma del contenido; JPEG/PNG además verifican dimensiones mediante ImageIO sin decodificar el bitmap. WebP comprueba contenedor RIFF/WEBP, longitud y tipo de chunk, sin conversión ni decodificador adicional. Sin antivirus, reencoding ni conversión automática.

ProductImageStorage genera products/{productId}/{uuid}.jpg|png|webp bajo MEDIA_STORAGE_PATH, configurado en producción como /opt/miqa-store/media. Guarda URL absoluta basada en MEDIA_BASE_URL (https://api-store.solucionesmicaela.com/media). Upload requiere base URL no vacía; responde 503 legible si falta. No endpoint de entrega local nuevo: el servidor/proxy de media configurado entrega los archivos; no se modificó Nginx/Cloudflare.

V4__product_image_upload.sql agrega active (true por defecto), storage_key nullable y constraint que impide principal inactiva. V1/V2/V3 y cinco productos seed intactos. storage_key solo se asigna por el servidor y no se expone en DTO. Quitar un upload elimina su archivo después de confirmar la transacción; rollback de upload elimina el archivo nuevo. Las claves admitidas son estrictas y se rechazan traversal y symlinks en ancestros. Nunca se deriva un archivo a borrar de url ni del nombre original; legacy/URLs manuales tienen storage_key null. Si el filesystem falla durante cleanup, se registra aviso genérico sin rutas y puede quedar un archivo huérfano que requiere revisión local del storage; no hay recolector automático.

MediaProperties preserva /images/... aunque MEDIA_BASE_URL esté configurada. Las referencias legacy no se migran ni se borran. URLs HTTP(S) absolutas se conservan. Frontend mantiene STORE_API_CONFIG.mediaBaseUrl vacío.

Errores: 400 vacío/tipo/contenido/metadatos inválidos; 413 tamaño; 404 producto/imagen ajenos o ausentes; 409 límite. Multipart máximo 5MB por archivo y 6MB por request. Respuestas no revelan paths internos.

Validación Java 21: Maven Wrapper test, 45 tests, 0 fallos, 0 errores, 0 omitidos; PostgreSQL exclusivamente miqa_store_test_db en 127.0.0.1:55432, storage @TempDir. Incluye JPG/PNG/WebP, MIME/contenido/extensión, tamaño/vacío, principal/orden/URL, límite manual y concurrente, autenticación, ownership, baja/legacy, traversal y rollback sin huérfanos. Flyway validó V1–V4 y Hibernate validate correcto. Package con -DskipTests tras suite completa aprobada genera target/miqa-store-backend-0.0.1-SNAPSHOT.jar. No aplicación de V4 en producción ni en miqa_store_db de desarrollo durante esta tarea.

Frontend: upload con preview/validación, tarjetas y detalle con hasta tres imágenes, puntos manuales y visor nativo accesible compartido. Pruebas de navegador usan respuestas interceptadas localmente; no son prueba integrada de despliegue. PostgreSQL aislado local queda disponible; procesos temporales de API de tests/navegador/servidor de revisión se cerraron.


## Ajuste a auditoría real VPS — 14 de septiembre de 2026

Estado vigente: preparación exclusivamente local. No conexión VPS, deploy, git add, commit, push, Cloudflare, SSH, frontend ni cambios ERP/LaserMonitor. Git inicial limpio; cambios de esta tarea sin staging. Esta sección actualiza las conclusiones previas.

Datos confirmados por auditoría del propietario: Ubuntu 26.04 LTS x86_64, 1 vCPU, RAM 1.6 GiB, swap 4.8 GiB, 29 GB libres. Java OpenJDK 21.0.12 global /usr/bin/java; Nginx 1.28.3; PostgreSQL 18.6 únicamente localhost:5432, max_connections=100 y TCP scram-sha-256. UFW activo incoming deny. ERP gigantografias.service / api.solucionesmicaela.com:8080 y LaserMonitor lasermonitor-backend.service / laser-api.solucionesmicaela.com:8081 no se tocan.

MIQA prod 127.0.0.1:8082 en perfil, guarda Java, Nginx y documentación. Local permanece 127.0.0.1:8081; tests HTTP aleatorios y DB local miqa_store_test_db:55432. DB productiva prevista miqa_store_db, rol miqa_store_app, loopback:5432. Sin apertura UFW para API/PostgreSQL. Frontend store.solucionesmicaela.com sigue en Cloudflare Workers Static Assets, no VPS.

Se conservan DB_* externas, JWT obligatorio, CORS APP_CORS_ALLOWED_ORIGINS exacto HTTPS, MEDIA_STORAGE_PATH/BASE_URL configurables, Hibernate validate, Flyway enabled/clean-disabled y Hikari 5/1. V1/V2/V3 sin diff. systemd y bootstrap usan /usr/bin/java -Xms64m -Xmx192m; sin JVM privada ni GC adicional. Heap no equivale a memoria total: medir RSS bajo carga antes del lanzamiento. Bootstrap conserva entrada oculta, no argumentos secretos, perfil explícito sin HTTP/Flyway y BCrypt sin sobrescribir usuarios; implementación ProductionAdminBootstrap intacta.

Nuevas plantillas: deploy/scripts/backup-miqa-store.sh y deploy/systemd/miqa-store-backup.service/.timer. Dump custom -Fc con pg_dump 18, timestamp UTC, archivo parcial/lock, pg_restore --list, fallo explícito y retención 14 días solo tras éxito. Backups root:root 0700/0600, inaccesibles al usuario API. Oneshot root sin capabilities y escritura persistente solo backups, conexión SQL miqa_store_app mediante PGPASSFILE/LoadCredential de backup.pgpass externo 0600; sin JWT ni password hardcodeado. Diario 03:00 UTC más hasta 15 minutos, Persistent=true. No backup/timer ejecutado. .gitattributes conserva LF Bash y añade LF para unidades.

Validación comprobada: Java 21, Maven Wrapper test y package BUILD SUCCESS; ambos 37 tests, 0 fallos/errores/omitidos. Flyway validó 3 migraciones, esquema al día exclusivamente en DB local de tests. Bash -n correcto para bootstrap y backup; git diff --check correcto, índice vacío. JAR target/miqa-store-backend-0.0.1-SNAPSHOT.jar generado. Logs privados ignorados .tmp/vps-audit-tests.log y .tmp/vps-audit-package.log; no publicados. Primer intento de tests interrumpido por PowerShell ErrorActionPreference=Stop al recibir aviso stderr de Mockito; repetición con manejo correcto del exit code Maven pasó, sin cambiar ni omitir tests.

Pendientes: validar unidades/Nginx en Linux, pg_dump/restore real PostgreSQL 18.6 y bootstrap interactivo completo; no realizados en Windows. TLS MIQA por decidir (ERP Origin Certificate, LaserMonitor Let's Encrypt), Cloudflare posterior, media /images y /media aún sin entrega, rate limiting/IP confiable, secretos/recuperación admin y permisos efectivos DB. Backups antes de cerrar despliegue: ensayo restauración, monitorización fallos/espacio y copia cifrada fuera del VPS bajo control propio; dump no respalda roles, configuración/JWT ni media. Guía deploy/README.md incluye comandos exactos futuros de instalación, bootstrap, backup/restauración y Nginx condicionado a TLS, ninguno ejecutado aquí.
## Preparación de producción LOCAL — 14 de septiembre de 2026

Estado vigente: archivos locales para futuro VPS, sin desplegar, crear remotos, commit/push, tocar Cloudflare/DNS, ERP ni PostgreSQL de producción. Frontend intacto: `https://store.solucionesmicaela.com` en Cloudflare Workers/Static Assets. API futura `https://api-store.solucionesmicaela.com`, Java 21/Spring Boot en VPS, bind `127.0.0.1:8082`; DB `miqa_store_db` y media propios, separados del ERP.

`application-prod.properties` requiere DB_* existentes, ADMIN_JWT_SECRET/EXPIRATION, APP_CORS_ALLOWED_ORIGINS, MEDIA_STORAGE_PATH/BASE_URL. Sin fallback de secretos, Hibernate validate, Flyway habilitado/clean deshabilitado, Hikari máximo 5/mínimo 1. Guardas previas al DataSource rechazan base de tests/ERP/remota, prod+local/test, bind público, DDL inseguro, JWT inválido y CORS no HTTPS. Desarrollo local conservado. Proxy headers mediante framework; el proxy confiable debe sobrescribirlos.

Bootstrap explícito `prod,admin-bootstrap`: non-web, Flyway deshabilitado, transacción/bloqueo de admin_users para crear únicamente la primera cuenta BCrypt. No sobrescribe/agrega si ya existe una. Arranque normal no registra este runner. Script Linux con entrada oculta/confirmación y variables efímeras; sin password en history/argumentos. No ejecutado contra producción.

Plantillas systemd, entorno y Nginx bajo deploy/, más guía `deploy/README.md`: usuario dedicado, Java 21, entorno externo root-only, journald y media escribible; nada instalado. Auditoría del propietario confirma Nginx 1.28.3 existente; TLS MIQA por decidir. Actuator solo health agregado, sin detalles/JMX/endpoints sensibles; bloqueado al exterior en ejemplo. Errores inesperados registran solo tipo de excepción, sin SQL/passwords/tokens.

V1/V2/V3 preservadas. V2 mantiene seis categorías/cinco productos como catálogo inicial; imágenes `/images/...` temporales. **MEDIA_BASE_URL del ejemplo aún apunta a un servicio inexistente**: resolver transición/entrega antes de publicar y actualizar referencias por admin/nueva migración. Sin upload/controlador media ni cambios a ProductImage. JAR `target/miqa-store-backend-0.0.1-SNAPSHOT.jar`; nombre estable al copiar para instalación, sin cambiar helpers.

Antes del VPS: inspeccionar OS/proxy/Java/systemd/puertos, TLS Full(strict)/confianza IP Cloudflare, aislamiento/permisos DB/backups, secretos y recuperación de admin, media y rate limiting login. Límite en memoria existente conservado, sin nueva solución improvisada. API base URL frontend cambiará solo cuando API prod funcione. Historial inferior supersedido por esta sección y deploy/README.md para producción.

Validación final Java 21: `mvnw.cmd test` y `mvnw.cmd package` BUILD SUCCESS; 36 tests, 0 fallos/errores/omitidos, solo PostgreSQL local aislado `miqa_store_test_db`. Flyway validó tres migraciones y esquema versión 3 al día; archivos V1/V2/V3 sin diff. Health HTTP devuelve únicamente status UP; endpoints sensibles bloqueados. Pruebas nuevas de configuración prod, JWT/CORS, bootstrap transaccional sin sobrescritura y arranque non-web, sin conexión de producción. Bash `-n` correcto. systemd/Nginx/TLS y bootstrap interactivo completo Linux no ejecutados. Logs ignorados `.tmp/prod-preparation-tests.log` y `.tmp/prod-preparation-package.log`. Git sin staging/commit; frontend limpio, backend sin remoto. JAR ejecutable generado; plantillas requieren revisión de infraestructura antes de instalar.

## Cierre de versionado local ? 13 de septiembre de 2026

Cierre Git LOCAL: repositorio inicializado en main, sin remoto. Se versionan Maven Wrapper, fuentes, scripts, migraciones V1/V2/V3 intactas y documentaci?n. Se excluyen .local/, .tmp/, target/, entornos/secretos locales, logs e IDE. Validaci?n Java 21: 26 tests y package correctos, tambi?n package desde una copia con solo archivos versionables. En producci?n se requerir? Java 21 instalado en el VPS; el JDK portable local no forma parte del repositorio. Sin push ni deploy.


## Panel administrativo local — 13 de septiembre de 2026

Estado vigente: frontend conectado a Store API y panel administrativo funcional. Reemplaza las referencias históricas a API de solo lectura/sin administración. Todo LOCAL; no ERP, gigantografias_db, producción, Cloudflare, commit, push o deploy.

### Autenticación y primer administrador

Spring Security con JWT HS256 mediante Nimbus/Spring Resource Server. Clave base64 de al menos 32 bytes aleatorios, issuer miqa-store-admin, audience miqa-store-admin-api, sub=id del administrador, iat/exp/jti. Expiración configurable de 1 minuto a 24 horas, default PT1H. Cada solicitud verifica además que AdminUser sigue activo. Password BCrypt cost 12; no registro ni recuperación. Se requieren variables de entorno, sin secreto de producción ni contraseña en Flyway.

V3__admin_users.sql crea admin_users (id, username único, password_hash, active, created_at, updated_at) y trigger de actualización. V1/V2 intactas. LocalAdminBootstrap solo existe con perfil local, requiere ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD y solo crea usuario si la tabla está vacía: nunca resetea contraseña ni reactiva usuarios existentes. Mínimo 12 caracteres y máximo 72 bytes UTF-8 para el bootstrap. El perfil test usa clave exclusiva de tests y usuarios BCrypt creados/limpiados en miqa_store_test_db.

scripts/Start-LocalAdmin.ps1 inicia/reutiliza PostgreSQL propio, carga DB, reutiliza o genera clave aleatoria en .local/admin-jwt.key (ignorado) y pide contraseña con Read-Host -AsSecureString. Solo se mantiene en memoria/entorno del proceso y se elimina de la sesión al terminar; no se escribe en archivos ni se imprime. El usuario sugerido es miqa-local; el propietario elige la contraseña al ejecutarlo. Credenciales temporales de validación no sirven como cuenta permanente.

```powershell
cd D:\MIQA-STORE\miqa-store-backend
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Start-LocalAdmin.ps1 -Username miqa-local
# Introducir una contraseña LOCAL propia (mínimo 12 caracteres).
# Abrir http://localhost:4200/admin/login, usuario miqa-local y esa contraseña.
```

La API debe estar detenida antes de iniciar otra instancia. Al terminar esta tarea se detiene la instancia usada para validar, dejando 8081 libre para el helper y PostgreSQL en 55432. El JDK portable existente es .tmp/jdk21/jdk-21.0.12.1+1; en otra máquina configurar JAVA_HOME con Java 21.

Variables nuevas:

| Variable | Uso |
| --- | --- |
| ADMIN_JWT_SECRET | Obligatoria, base64 de al menos 32 bytes aleatorios; helper local la carga de archivo ignorado. |
| ADMIN_JWT_EXPIRATION | Duration ISO-8601; PT1H por defecto, entre PT1M y PT24H. |
| ADMIN_BOOTSTRAP_USERNAME | Solo perfil local y tabla vacía. |
| ADMIN_BOOTSTRAP_PASSWORD | Solo bootstrap local, nunca persistida sin BCrypt ni registrada. |

Login aplica límite en memoria de cinco fallos por usuario/minuto y máximo de entradas pendientes; 429 al superar el límite. No reemplaza rate limiting compartido/proxy antes de producción. Login/Session tienen toString redactado, se impide DEBUG de cuerpos MVC en perfil local y los diagnósticos temporales de autenticación de la primera prueba se redactaron. No loguear headers Authorization, passwords ni tokens aun al diagnosticar.

### API administrativa

Todos los endpoints /api/admin/** requieren Bearer salvo POST /api/admin/auth/login. Sin cookies ni HttpSession; CSRF deshabilitado únicamente por usar autenticación Bearer en headers. /api/public/** sigue abierto. CORS exacto localhost:4200 permite Authorization y GET/POST/PUT/PATCH/OPTIONS para admin; no wildcard ni credenciales de cookie.

- POST /api/admin/auth/login → {token, expiresAt}; GET /api/admin/auth/me → {id, username}.
- GET/POST /api/admin/categories; GET/PUT /api/admin/categories/{id}; PATCH /{id}/active con {active}.
- GET/POST /api/admin/products; GET/PUT /api/admin/products/{id}; PATCH /{id}/published con {published}; PATCH /{id}/featured con {featured}.
- Listado admin de productos admite search, category (ID de categoría), published y featured combinados, orden displayOrder/id. La API pública conserva category como SLUG. Sin paginación en esta etapa.
- GET/POST /api/admin/products/{pid}/materials y /extras; PUT /{optionId}; PATCH /{optionId}/active con {active}.
- GET/POST /api/admin/products/{pid}/images; PUT /{imageId}; PATCH /{imageId}/primary con {primaryImage}.
- No endpoints DELETE físicos. Opciones se desactivan; productos se despublican. Categorías inactivas ocultan sus productos sin cambiar published almacenado.

AdminCatalogService es transaccional y devuelve DTOs (AdminDtos), no entidades. Nuevos IDs UUID string, compatibles con IDs seed. Cambios mínimos en entidades existentes: constructores/setters y callbacks timestamps para persistencia JPA. Product/Category repositorios validan slug excluyendo ID actual; restricciones DB mantienen seguridad ante carreras y duplicados devuelven 409. Option IDs siempre se buscan dentro del producto solicitado, con 404 si pertenecen a otro. Máximo una imagen primaria: bloqueo de fila del producto, limpieza/flush de principal anterior y escritura en la misma transacción; índice único de V1 conservado.

QUANTITY/AREA exigen packSize/packLabel null; PACK exige packSize positivo y packLabel no vacío. Cantidades/paso positivos, orden no negativo, slugs válidos/únicos, tamaños máximos y campos requeridos validados. SEO se guarda/administra; no se activa indexación ni se altera el SEO público en esta etapa. Errores ApiError consistentes, 400 con campos, 401/403/404/409/429, 500 sin stacktrace para cliente.

Media: solo referencias URL HTTP(S) o path relativo seguro, validación MediaProperties; admin devuelve url original y publicUrl resuelta. No upload, escrituras de archivo de producto ni endpoint /media. No se consulta el recurso externo desde backend. Persisten arquitectura VPS y base URL configurable. Las imágenes de prueba /images/... son referencias TEMPORALES, no arquitectura definitiva.

### Validación y límites

26 tests backend (sin fallos/errores/omitidos), Maven test y package Java 21 correctos. Suite AdminApiTest usa DB real de tests y prueba autenticación/expiración/usuario inactivo/401/CORS/throttle, categorías, productos, filtros/visibilidad/SEO/duplicados/saleType, materiales/extras y ownership, referencias y principal única. Suite pública y constraints siguen pasando. Flyway V1/V2/V3 con success en miqa_store_db y tests; Hibernate validate correcto.

Frontend: 61 tests, build correcto y tres rutas públicas prerenderizadas; admin CSR. Prueba Edge local completa: login → categoría → producto → edición → materiales/extra/imagen → publicar → visible público → despublicar → ausente público → logout → guard. Responsive 360/390/768/1024/1440/1920, sin overflow en productos/categorías/formulario; axe sin infracciones en esas vistas a 390/1440 y sin errores JS inesperados. Evidencias ignoradas en frontend .tmp/admin-browser-check.json y capturas admin-*.png; backend .tmp/admin-tests.log y admin-package.log.

La validación creó registros con slug admin-local-check-<timestamp>, nombres Prueba administrativa local / Producto prueba local editado. Al finalizar todos esos productos quedan published=false y sus categorías active=false; materiales/extras/imágenes solo asociados a esos borradores. Las cinco fichas seed siguen visibles. Administrador efímero admin-e2e-local eliminado tras cada prueba; no se entrega contraseña de tests. Crear la cuenta propia con Start-LocalAdmin.ps1.

Antes de producción: secreto nuevo gestionado fuera del repo y rotación, TLS y proxy VPS, CORS exacto del dominio real, provisión de admin sin bootstrap local, permisos PostgreSQL mínimos/backups, rate limiting persistente/proxy, revisión de logs y política de sesión. JWT logout elimina copia del navegador pero no revoca por token hasta expirar (desactivar usuario revoca acceso a todos sus tokens); sin refresh tokens/blacklist. Frontend usa localStorage y por ello comparte el riesgo de XSS: revisar CSP y considerar sesión con cookie HttpOnly + CSRF/BFF en una fase posterior. No guardar claves locales ni perfiles de navegador en Git.

Deuda: listados sin paginación; ediciones de producto/categoría siguen last-write-wins sin versión optimista ni historial de auditoría. Confirmación de cambio de tipo explícita en UI; no hay guard global de formularios con cambios sin guardar. Upload físico, entrega media VPS, recuperación de contraseña, múltiples roles, pagos/órdenes/ERP/SUNAT y analítica siguen fuera de alcance.




Validación integrada final tras retomar (13/09/2026): el paquete actualizado pasó login/guard, creación de categoría y producto, edición, material/extra/imagen principal, publicación visible en /productos, edición posterior reflejada en ficha pública, despublicación y logout. Sin errores JS inesperados; sin overflow en 360/390/768/1024/1440/1920; axe sin infracciones en listado, categorías y edición a 390/1440. Se conservaron las capturas y evidencia en .tmp/admin-browser-check.json y admin-*.png.

Estado final verificado en PostgreSQL: dos productos con slugs admin-local-check-1789281049545 y admin-local-check-1789358366249, ambos published=false; categorías homónimas active=false. Sus opciones/imágenes solo pertenecen a esos borradores. Cinco productos originales permanecen públicos. Usuario admin-e2e-local eliminado, admin_users vacío, preparado para crear cuenta propia con Start-LocalAdmin.ps1. No se conservó una contraseña de pruebas utilizable. Flyway V1/V2/V3 success en ambas bases; sin migraciones adicionales en la reanudación.

Backend de validación detenido y puerto 8081 libre; PostgreSQL propio sigue activo en 55432. Frontend disponible en localhost:4200. Para iniciar sesión por primera vez, arrancar backend con el helper y elegir contraseña propia; no hay admin/password predeterminados. Documentación backend README/PROJECT_CONTEXT y frontend PROJECT_CONTEXT completada; no hubo cambios de implementación ni errores de compilación que requirieran rehacer archivos durante esta reanudación.

Actualizado: 12 de septiembre de 2026. Trabajo exclusivamente local, sin Git inicializado, commit, push ni deploy. Frontend y ERP intactos.

## Trabajo heredado y continuación

Al retomar existían pom.xml (Spring Boot 4.0.8, release Java 21), Maven Wrapper 3.9.9, entidades Category/Product/ProductMaterial/ProductExtra/ProductImage, enum, repositorios, DTOs, servicio/controlador público, CORS, errores y guard local de DB, migrations V1/V2 y scripts PostgreSQL. No había tests, documentación, clúster inicializado ni build validado. Se conservaron esos archivos, corrigiendo BOM UTF-8 que impedía compilar.

La continuación añadió tests reales HTTP/PostgreSQL, MediaProperties y resolución de URLs en DTO, README/AGENTS/contexto; inició un clúster aislado y corrigió el helper de arranque para separar handles de la terminal. Validación final en Temurin Java 21.0.12.1: **16 tests, 0 fallos, 0 errores, 0 omitidos**, Maven Wrapper test y package correctos. Primero se validó en Java 23 y después en Java 21 sin cambiar el Java del sistema. Flyway V1/V2 y Hibernate validate correctos tanto en miqa_store_test_db como en miqa_store_db.

JAR: target/miqa-store-backend-0.0.1-SNAPSHOT.jar, aproximadamente 59 MB. Se inició con Java 21 en 127.0.0.1:8081 y se comprobaron mediante Invoke-RestMethod seis categorías, cinco productos, filtros combinados, detalle de Vinil (tres materiales/dos extras) y 404 desconocido. Luego se detuvo solo ese proceso, verificando su ruta; **8081 queda libre**. PostgreSQL de .local permanece levantado en 127.0.0.1:55432. Logs/evidencia en .tmp/tests-java21.log, package-java21.log, api.log y product-example.json, sin versionar. El helper se volvió a ejecutar y reconoció la instancia existente sin recrear datos.

## Arquitectura objetivo

Angular permanece en Cloudflare Workers/Static Assets. API Spring Boot en VPS, posible dominio api-store.solucionesmicaela.com (sin configurar). PostgreSQL miqa_store_db en VPS/infraestructura propia, completamente separado del ERP/gigantografias_db. Media también en VPS propio, no Cloudinary/Firebase/S3. No hay integración entre sistemas.

Local: puerto HTTP 8081, bind 127.0.0.1. PostgreSQL 17 instalado se utiliza solo como binario para crear .local/postgres, puerto 55432/127.0.0.1, usuario/contraseña de desarrollo generados y guardados en .local/connection.json, ignorado. No leer/imprimir ese archivo al usuario. Base app miqa_store_db; tests miqa_store_test_db. No se toca el servicio PostgreSQL existente. scripts/Start-LocalPostgres.ps1 es idempotente, Use-LocalDatabase.ps1 carga variables de sesión y Stop-LocalPostgres.ps1 detiene solo el clúster propio.

## Diseño y contrato

Capas controller → servicio readOnly transaccional → repositorios JPA → PostgreSQL; DTOs records, nunca entidades serializadas. Lazy associations y BatchSize para colecciones/categoría, sin Open Session In View. Todos los endpoints son GET públicos:
- /api/public/categories: active y orden displayOrder/id.
- /api/public/products: published y categoría activa; filtros combinables category, search, featured. Búsqueda por nombre case-insensitive, acentos significativos, SQL wildcard escapado; featured admite true/false.
- /api/public/products/{slug}: misma visibilidad, opciones activas e imágenes ordenadas; 404 si no está visible/no existe.

IDs string preservan mocks; DTO incluye categorySlug y category, image/gallery e images con alt, published/featured, saleType/unitLabel/packSize/packLabel/minQuantity/step y arrays materials/extras. Opcionales nullable; Angular aún usa mocks y no fue modificado/conectado. No hay paginación en esta etapa; considerarla antes de catálogos grandes.

Errores JSON consistentes timestamp/status/code/message/path/errors. No stacktrace al cliente. Validación de filtros y slug, 400/404/405 y 500 genérico. CORS solo localhost:4200 con perfil local; fuera de local lista configurable exacta, sin wildcard ni credenciales. No es autenticación.

## Esquema y migraciones

V1__initial_schema.sql: categories/products/materials/extras/images, slugs únicos y no vacíos, enum de venta por check, presentación PACK íntegra, cantidades positivas, órdenes no negativos, FK/índices, máximo una imagen primaria por producto, timestamps con triggers updatedAt. Categoría referenciada usa RESTRICT; hijos de producto CASCADE solo para mantenimiento SQL explícito. Futuro admin debe despublicar/desactivar antes que borrar. Sin precios.

V2__seed_initial_catalog.sql: seis categorías y cinco productos EXACTAMENTE derivados de datos mock frontend (textos/ids/slugs/imagen/materiales/extras). Ya aplicada en tests: no editar estas migraciones, añadir una V3 si hay cambios. ddl-auto=validate; Flyway clean deshabilitado. EnvironmentPostProcessor bloquea hosts remotos y nombres distintos de miqa_store_db/miqa_store_test_db antes de crear DataSource. Al preparar despliegue VPS deberá revisarse expresamente si el host de PostgreSQL es diferente de loopback.

## Media: referencias independientes de Angular

ProductImage.url es string con referencia relativa o URL HTTP(S), no filesystem de frontend. MediaProperties enlaza app.media.storage-path/MEDIA_STORAGE_PATH (default .local/media) y app.media.base-url/MEDIA_BASE_URL (vacío por defecto), con validación de esquema/origen. CatalogService resuelve URLs públicas sin alterar la referencia guardada. storage-path está reservado: no se escriben ni sirven archivos todavía.

Las rutas /images/... de V2 son TEMPORALES para compatibilidad visual; la API no sirve ni copia esos archivos y no depende de Angular para arrancar. Futuro admin: subir → validar → guardar en VPS bajo storage-path → referencia en PostgreSQL → URL con base-url → entrega pública por backend/proxy. El dominio API/media y topología de entrega aún no se deciden; son configurables. No se implementaron upload, /media, autenticación ni almacenamiento externo.

## Validación y límites

CatalogApiTest usa servidor HTTP aleatorio y PostgreSQL real con base fija de tests; comprueba seis categorías, cinco productos, filtros combinables, opciones activas, invisibilidad de borradores/categoría inactiva, JSON/errores, CORS y restricciones/historial Flyway. Fixtures reservados se limpian después de cada test. ConfigurationTest verifica media, protección DB y errores inesperados sanitizados. No H2 ni Testcontainers; tests fallan si falta PostgreSQL/credenciales, no se omiten.

JDK 21 Temurin portable descargado a .tmp/jdk21/jdk-21.0.12.1+1 y checksum SHA-256 verificado para validación; Java del sistema no se cambia. .tmp, .local, target y secretos ignorados. README contiene comandos de arranque/pruebas y alternativa de DB local manual. No frontend, Cloudflare, ERP, producción, upload, admin, autenticación, pedidos, pagos ni WhatsApp Cloud API implementados en esta etapa. No hay cambios que requieran aprobación destructiva; siguiente integración/despliegue requiere nueva tarea. La ejecución de tests emite un aviso de Mockito sobre futura necesidad de javaagent y logs deliberados del caso 500/constraints; Maven finaliza correctamente, no se omiten ni silencian fallos.
