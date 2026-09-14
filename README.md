# MIQA Store API

## Preparación de producción LOCAL — 14 de septiembre de 2026

Perfil `prod` y plantillas VPS preparados, **sin deploy, VPS, Cloudflare, DNS, commit, push ni remote nuevo**. Frontend sigue en Cloudflare (`https://store.solucionesmicaela.com`); API futura `https://api-store.solucionesmicaela.com` en VPS, loopback `127.0.0.1:8082`, PostgreSQL `miqa_store_db` y media propios, separados del ERP.

VPS auditado: Java 21 global /usr/bin/java, Nginx existente, PostgreSQL local 5432. MIQA prod 8082 interno; local conserva 8081. No abrir UFW ni tocar ERP/LaserMonitor. Heap systemd 64–192 MiB. Backup diario y retención 14 días preparados, pendientes de ensayo. TLS/Cloudflare se decidirán después; frontend fuera del VPS.

Guía vigente: [deploy/README.md](deploy/README.md), con variables, PostgreSQL dedicado, Java 21, systemd, bootstrap Linux manual, proxy de referencia y pasos futuros. `deploy/env/miqa-store.env.example` solo contiene placeholders; archivos reales `*.env` y claves privadas están ignorados. Se conservan `DB_*`; prod usa `APP_CORS_ALLOWED_ORIGINS` con orígenes HTTPS exactos, sin credentials. JWT exige secreto externo base64 de al menos 32 bytes; no hay fallback ni admin predeterminado.

Flyway V1/V2/V3 intactas y Hibernate `validate`. V2 se conserva como catálogo inicial, pero sus referencias temporales `/images/...` requieren resolver entrega antes de publicar: el ejemplo `MEDIA_BASE_URL=.../media` **aún no sirve archivos**. Sin upload ni cambios a ProductImage. Actuator expone solo health agregado; el ejemplo de proxy lo bloquea públicamente. Logs a stdout/journald, sin cuerpos de auth ni mensajes/causas de excepciones inesperadas.

JAR Maven conservado: `target/miqa-store-backend-0.0.1-SNAPSHOT.jar`; futura instalación como `/opt/miqa-store/app/miqa-store-backend.jar`. No cambió `finalName` ni helpers locales. Validar con Java 21, helper de DB aislada, `./mvnw.cmd test` y `./mvnw.cmd package`; nunca contra producción.

Las secciones siguientes son historial local: sus afirmaciones antiguas sobre ausencia de prod/admin/Git no representan el estado vigente. Resultados actuales al inicio de `PROJECT_CONTEXT.md`.

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

## Referencia hist?rica del cat?logo p?blico

Las secciones siguientes describen la base original. Para administraci?n, autenticaci?n, variables y validaci?n actual, usar la secci?n anterior.

﻿# MIQA Store API

Backend local de catálogo, Spring Boot 4.0.8, Maven Wrapper 3.9.9 y Java 21. API de solo lectura; sin autenticación, administración, precios, pagos, pedidos, integración ERP ni subida de archivos.

## Arquitectura

Producción futura: Angular en Cloudflare Workers/Static Assets → API Spring Boot en VPS → PostgreSQL `miqa_store_db` en infraestructura propia. `api-store.solucionesmicaela.com` es un posible dominio futuro; no está configurado aquí. ERP y `gigantografias_db` permanecen separados. Las imágenes también pertenecerán al VPS/infraestructura propia, sin Cloudinary, Firebase Storage o S3.

En esta etapa la aplicación escucha solamente en `127.0.0.1:8081`. Una protección anterior a DataSource/Flyway rechaza bases distintas de `miqa_store_db` y `miqa_store_test_db`, o hosts diferentes de localhost/127.0.0.1. No se habilita conexión remota ni despliegue de producción.

## Requisitos

- JDK 21, `JAVA_HOME` apuntando a su directorio; `java -version`.
- PostgreSQL 17 local. Maven no necesita instalación: usar `mvnw.cmd` en Windows o `./mvnw` en Unix.
- Primera ejecución de Maven necesita Internet para descargar herramientas/dependencias oficiales.
- Los tests de integración necesitan PostgreSQL real. No se sustituyó por H2 ni se omiten tests automáticamente.

## PostgreSQL aislado en Windows (opción utilizada para validar)

Los scripts usan los binarios de PostgreSQL existentes, pero crean **su propio clúster** en `.local/postgres`, sin registrar un servicio ni usar el clúster existente. Puerto 55432, enlace solo 127.0.0.1. Si el puerto está ocupado, se detienen sin alterar otro servidor.

```powershell
cd D:\MIQA-STORE\miqa-store-backend
# Solo si la política local impide ejecutar los scripts revisados, para esta sesión:
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\Start-LocalPostgres.ps1
# Si los binarios están en otro lugar:
# .\scripts\Start-LocalPostgres.ps1 -PostgresBin 'C:\ruta\postgres\bin'
```

Crea `miqa_store_db` y `miqa_store_test_db`. Genera una contraseña aleatoria para el usuario del clúster aislado, sin inferir credenciales existentes. `.local/connection.json` guarda esas credenciales de desarrollo y está excluido por `.gitignore`: no compartir ni versionar `.local`. No es una configuración de credenciales de producción. El usuario inicial es superusuario solo de este clúster de desarrollo; producción deberá separar permisos de migración y lectura.

Para cargar variables en una **nueva terminal**, sin recrear el clúster:

```powershell
.\scripts\Use-LocalDatabase.ps1
```

Para detener exclusivamente este clúster:

```powershell
.\scripts\Stop-LocalPostgres.ps1
```

No se necesita Docker. Si se decide usar otro PostgreSQL local, crear roles/DB nuevos expresamente para Store, nunca reutilizar una base del ERP. Desde `psql` conectado a una instancia local autorizada:

```sql
CREATE ROLE miqa_store_local LOGIN;
\password miqa_store_local
CREATE DATABASE miqa_store_db OWNER miqa_store_local ENCODING 'UTF8';
CREATE DATABASE miqa_store_test_db OWNER miqa_store_local ENCODING 'UTF8';
```

Ese procedimiento alternativo requiere las credenciales del administrador de **esa instancia local**, que no están incluidas aquí. No ejecutarlo contra ERP o producción.

## Variables

| Variable | Uso / valor por defecto |
| --- | --- |
| `DB_HOST` | `localhost`; etapa local únicamente |
| `DB_PORT` | `5432`; helper aislado establece `55432` |
| `DB_NAME` | `miqa_store_db` |
| `DB_USERNAME`, `DB_PASSWORD` | Obligatorias; sin valores secretos en properties |
| `SPRING_PROFILES_ACTIVE` | `local` para permitir origen `http://localhost:4200` |
| `TEST_DB_PORT` | `55432`, siempre host 127.0.0.1 y DB `miqa_store_test_db` |
| `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` | Credenciales de la base exclusiva de tests |
| `MEDIA_STORAGE_PATH` | `.local/media`, carpeta futura de almacenamiento VPS |
| `MEDIA_BASE_URL` | Vacío: conserva referencias; configurar URL HTTP(S) propia cuando exista servidor de media |
| `CORS_ALLOWED_ORIGINS` | Vacío fuera del perfil local; lista de orígenes exactos, separados por coma, sin wildcard |

`application-local.properties` permite solo localhost:4200. Se podrá configurar posteriormente el origen `https://store.solucionesmicaela.com` mediante variables **fuera del perfil local**; no se ha configurado producción. CORS permite GET/HEAD/OPTIONS, sin credenciales. CORS no equivale a autenticación.

## Ejecutar y validar

Con PostgreSQL iniciado y variables cargadas:

En esta máquina quedó disponible un JDK 21 portable verificado en `.tmp` (ignorado), sin modificar el Java 23 del sistema. Para reutilizarlo en la sesión actual:

```powershell
$env:JAVA_HOME = (Resolve-Path '.tmp\jdk21\jdk-21.0.12.1+1').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

En otra máquina, establecer JAVA_HOME al JDK 21 instalado en ella.

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target\miqa-store-backend-0.0.1-SNAPSHOT.jar
# Alternativa de desarrollo:
# .\mvnw.cmd spring-boot:run
```

`package` incluye tests. El artefacto compilado usa `--release 21`. Para repetir únicamente el empaquetado después de tests aprobados, `./mvnw.cmd -DskipTests package` omite tests de forma explícita.

Base URL: `http://127.0.0.1:8081`. En otra terminal:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8081/api/public/categories'
Invoke-RestMethod 'http://127.0.0.1:8081/api/public/products?category=imprenta-papeleria&search=tarjetas&featured=true'
Invoke-RestMethod 'http://127.0.0.1:8081/api/public/products/vinil-impreso'
```

Los tests levantan la API en puerto aleatorio y hacen peticiones HTTP reales contra JPA/PostgreSQL. Usan y limpian fixtures únicamente en `miqa_store_test_db`; no ejecutar contra una DB con información importante.

## Endpoints y contrato

- `GET /api/public/categories`: categorías activas por displayOrder/id.
- `GET /api/public/products`: productos publicados de categorías activas, por displayOrder/id. Filtros opcionales combinables: `category` (slug), `search` (nombre, sin distinguir mayúsculas; acentos significativos), `featured=true|false`. `%` y `_` se buscan literalmente. Sin paginación en esta primera etapa.
- `GET /api/public/products/{slug}`: producto publicado con categoría, imágenes, materiales/extras activos y ordenados. Desconocidos u ocultos devuelven 404.

Se mantienen ids string de mocks y campos `categorySlug`, `published`, `image`, `gallery`, `step`. Se añaden `category` e `images` con metadata. No se serializan entidades JPA. `image` usa la imagen primaria o, si no existe, la primera por orden; sin imágenes devuelve cadena vacía. `gallery` excluye la principal. Valores opcionales pueden ser null: al integrar Angular, normalizarlos si el consumidor exige undefined. El frontend todavía usa sus mocks.

```json
{
  "id": "tarjetas-personales",
  "slug": "tarjetas-personales",
  "name": "Tarjetas personales",
  "categorySlug": "imprenta-papeleria",
  "image": "/images/products/tarjetas-personales.png",
  "featured": true,
  "published": true,
  "saleType": "PACK",
  "unitLabel": "unidad",
  "packSize": 1000,
  "packLabel": "millar",
  "minQuantity": 1,
  "step": 1,
  "materials": [],
  "extras": []
}
```

Ejemplo abreviado: la respuesta completa también incluye descripciones, categoría, gallery e images. Errores tienen `timestamp`, `status`, `code`, `message`, `path`, `errors`; nunca stack trace ni detalles internos. Parámetros inválidos → 400, recurso oculto/inexistente → 404, métodos de escritura → 405, fallo inesperado → 500 genérico (detalle solo en log del servidor).

## Esquema y Flyway

- `categories`: slug único, active, orden, timestamps.
- `products`: categoría FK, slug único, tipo QUANTITY/PACK/AREA, presentación, mínimos/paso, featured/published, orden, SEO opcional y timestamps. Sin precios.
- `product_materials`, `product_extras`: producto FK, nombre, active y orden.
- `product_images`: producto FK, referencia `url`, altText, primaria y orden. Máximo una primaria por producto mediante índice parcial único.

`V1__initial_schema.sql` crea tablas, constraints, índices y triggers de updatedAt. Categoría con productos: DELETE RESTRICT. Los hijos de un producto: ON DELETE CASCADE, para mantenimiento explícito futuro; no hay endpoints de borrado. La operación de aplicación futura deberá despublicar/desactivar.

`V2__seed_initial_catalog.sql` carga seis categorías y cinco productos con materiales/extras e imágenes equivalentes a los mocks actuales. Flyway conserva versiones/checksums; no editar una migración ya aplicada, añadir una versión nueva. `ddl-auto=validate`, sin auto-DDL create/update; Flyway clean deshabilitado.

## Media en infraestructura propia

`ProductImage.url` es una referencia de recurso, **no una dependencia del filesystem Angular**. Puede guardar una URL absoluta HTTP(S) o una referencia relativa como `products/archivo.webp`. `MediaProperties` construye las URLs públicas usando `app.media.base-url`; `app.media.storage-path` reserva la carpeta donde un futuro servicio guardará archivos. La base puede apuntar al dominio de API o a un dominio propio de media; no se ha decidido ni hardcodeado esa topología.

**TEMPORAL:** V2 usa `/images/...` de los mocks para compatibilidad visual al consumir desde Angular. Esta API no sirve esos archivos y no contiene/copió los assets del frontend. No es la arquitectura definitiva. Al contar con media propia, una nueva migración o futura administración sustituirá las referencias; `MEDIA_BASE_URL` debe corresponder al servidor realmente habilitado.

Flujo futuro previsto: administrador autorizado → endpoint de subida → validación de tipo/tamaño y nombre seguro → guardar archivo bajo storage-path en VPS → guardar referencia en PostgreSQL → resolver URL pública con base-url → servir mediante backend o proxy del VPS. **No implementado aún:** upload, escrituras de archivo, entrega de `/media`, permisos de administrador y gestión de eliminación/huérfanos. No se expone automáticamente ninguna carpeta local.

## Estado y próximos pasos

Validado con Java 21: **16 tests aprobados, ninguno omitido**, package correcto, Flyway V1/V2 correctos en ambas bases, Hibernate validate y peticiones HTTP reales en 8081 correctos. La API de prueba se detuvo después de validar; PostgreSQL aislado queda levantado en 55432. Los avisos de Mockito en tests no son fallos de Maven. Ver `PROJECT_CONTEXT.md` para evidencias y límites.

No se inicializó Git, no hay commit/push/deploy. Pendiente de otra autorización: conectar Angular a esta API, autenticación/admin, gestión de archivos y despliegue VPS con TLS y permisos apropiados. No hay decisiones de producción que deban ejecutarse en esta etapa.
