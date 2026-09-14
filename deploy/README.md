# Preparación VPS — NO desplegado

Estado: plantillas preparadas localmente el 14/09/2026. No se ha inspeccionado ni modificado el VPS, PostgreSQL de producción, Cloudflare, DNS o ERP. **Los comandos de esta guía son pasos futuros, no ejecutados.** Las secciones históricas del README/contexto anteriores a esta fecha no describen la preparación actual.

## Arquitectura y límites

Frontend existente: `https://store.solucionesmicaela.com`, Angular en Cloudflare Workers/Static Assets. API futura: `https://api-store.solucionesmicaela.com` → Cloudflare → TLS en reverse proxy del VPS → `127.0.0.1:8081` → PostgreSQL local `miqa_store_db`. Media futura también en infraestructura propia. No integrar ni modificar ERP, `gigantografias_db`, sus usuarios, puertos, servicios o DNS.

La documentación no identifica Nginx, Apache o Caddy como proxy realmente instalado. `nginx/api-store.conf.example` es solo una referencia Nginx; **no instalar antes de inspeccionar infraestructura**. No hay automatización de deploy.

## Configuración requerida

`application-prod.properties` conserva la convención existente `DB_*`. No mezclar con nombres `STORE_DB_*`. Los valores propios del entorno son obligatorios, sin fallback de desarrollo. Las políticas de seguridad (loopback, validate, Flyway) están declaradas en el perfil.

| Variable | Valor futuro / significado |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod`; systemd también fija el perfil en el comando |
| `DB_HOST` | `127.0.0.1` (solo loopback permitido) |
| `DB_PORT` | `5432`, confirmar puerto de la instancia autorizada |
| `DB_NAME` | `miqa_store_db`; prod rechaza la base de tests y ERP |
| `DB_USERNAME` | Rol dedicado MIQA, nunca postgres ni usuario ERP |
| `DB_PASSWORD` | Secreto externo obligatorio |
| `ADMIN_JWT_SECRET` | Base64 de al menos 32 bytes aleatorios; no reutilizar secretos locales/tests |
| `ADMIN_JWT_EXPIRATION` | `PT1H` sugerido, ISO-8601 entre `PT1M` y `PT24H` |
| `APP_CORS_ALLOWED_ORIGINS` | `https://store.solucionesmicaela.com`; lista separada por comas, HTTPS exacto |
| `MEDIA_STORAGE_PATH` | `/opt/miqa-store/media`; path absoluto Linux |
| `MEDIA_BASE_URL` | URL futura de media propia; ejemplo `https://api-store.solucionesmicaela.com/media`, aún NO operativa |

`CORS_ALLOWED_ORIGINS` se conserva para configuración base/local; en **prod** se usa `APP_CORS_ALLOWED_ORIGINS`. No poner `ADMIN_BOOTSTRAP_*` en el archivo persistente. Perfil prod no admite `local`/`test` combinados. Guardas antes de DataSource/Flyway rechazan configuración vacía/placeholders, JWT corto o inválido, CORS inseguro, DB ajena, bind público y DDL create/update. HS256, issuer/audience y verificación de usuario activo se conservan. Hikari usa máximo 5 conexiones, mínimo 1; revisar capacidad junto con otros servicios antes de aumentar.

El ejemplo `env/miqa-store.env.example` no contiene secretos. Copiarlo **posteriormente** a `/etc/miqa-store/miqa-store.env`, root:root, modo 0600 y directorio 0700. Editar con una herramienta que no cree copias inseguras, sin introducir secretos en la línea de comandos. Es sintaxis systemd `EnvironmentFile`, no un script: sin `export`, sin expansiones de shell; entrecomillar valores según systemd cuando corresponda. No hacer `source` del archivo. `*.env`, `.env*`, claves privadas, `.local`, `.tmp` y `target` están ignorados; los ejemplos permanecen versionables.

Comando para generar un JWT fuerte **en una futura sesión segura**, no ejecutado durante esta preparación:

```bash
openssl rand -base64 48
```

Guardar posteriormente su salida directamente en el gestor/archivo protegido, nunca en Git, chat, tickets, logs o historial. Los entornos de procesos no son una bóveda: root y procesos del mismo usuario pueden inspeccionarlos. El usuario dedicado y permisos externos son parte del diseño; considerar credenciales systemd/gestor de secretos más adelante.

## Directorios y Java 21

```text
/opt/miqa-store/
  app/miqa-store-backend.jar        # root:miqa-store, 0640; app 0750
  java21 -> JDK 21 instalado       # enlace administrado por root, según distribución
  media/                          # miqa-store:miqa-store, 0750
  scripts/bootstrap-admin.sh       # root:miqa-store, 0750; ejecutar con bash
/etc/miqa-store/miqa-store.env      # root:root, 0600; directorio 0700
```

No se necesita carpeta logs: stdout/stderr van a journald. La aplicación no debe poder modificar JAR, scripts, configuración ni Java. `media` es la única carpeta persistente escribible del servicio; todavía no se escriben imágenes. No crear carpeta de configuración duplicada bajo `/opt`.

El artefacto Maven sigue siendo `target/miqa-store-backend-0.0.1-SNAPSHOT.jar`. Se conserva `finalName` actual para no afectar helpers existentes. Al copiarlo en el futuro se renombra a `miqa-store-backend.jar`; sigue siendo un JAR ejecutable Spring Boot. Verificar checksum antes/después de la transferencia y conservar el JAR anterior para rollback. Un rollback del JAR no revierte migraciones: revisar compatibilidad y backup antes de cada actualización.

## PostgreSQL — instrucciones futuras, no ejecutadas

Primero confirmar instancia/puerto/versión, capacidad, backup/restauración y que los nombres estén libres. No reutilizar usuario ERP ni ejecutar contra `gigantografias_db`. Conectarse después como administrador de la **instancia autorizada**, sin cambiar `pg_hba.conf` en este bloque:

```sql
-- En psql; \password solicita la contraseña sin dejarla como literal SQL/historial.
CREATE ROLE miqa_store_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
\password miqa_store_app
CREATE DATABASE miqa_store_db OWNER miqa_store_app ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE miqa_store_db FROM PUBLIC;
\connect miqa_store_db
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO miqa_store_app;
```

Asignar `DB_USERNAME=miqa_store_app` en el entorno protegido. La app no usa el rol postgres. Primera versión: rol dueño solo de Store para ejecutar Flyway al arrancar; no superusuario ni permisos de creación de DB/roles. Deuda explícita: separar credenciales migrador/runtime si se desea mínimo privilegio DML. Crear un rol nuevo no revoca permisos PUBLIC en otras bases existentes; revisar aislamiento efectivo con el administrador **sin modificar bases ERP en esta tarea**. Para aislamiento adicional, valorar instancia independiente y política de acceso en otro bloque. No crear base de tests en producción. API/DB en mismo VPS: conexión loopback; no abrir PostgreSQL a Internet.

## Flyway

- **V1:** esquema catálogo, constraints, FK, índices, función/triggers; requiere permisos CREATE del dueño MIQA. Sin extensión ni objetos del ERP.
- **V2:** seis categorías y cinco productos publicados del catálogo vigente, con materiales/extras. Se conserva como bootstrap productivo del catálogo. **Advertencia:** referencias `/images/...` temporales; el comentario histórico “local” no impide que Flyway ejecute los inserts en prod. No contiene usuarios ni contraseñas. Antes del lanzamiento hay que resolver dónde se sirven estas imágenes.
- **V3:** solo tabla `admin_users` y trigger; no INSERT, administrador ni password predeterminado.

V1/V2/V3 no se reescriben. En arranque normal prod, Flyway aplica pendientes/valida checksums y Hibernate usa `validate`; clean deshabilitado. Bootstrap manual deshabilita Flyway y exige esquema ya migrado. No usar repair/baseline para ocultar diferencias. Cambios futuros de referencias: administración explícita o nueva migración, nunca editar V2 aplicada.

## Servicio y primer administrador

`systemd/miqa-store.service` es una plantilla, no instalada. Usuario/grupo `miqa-store`, Java 21 mediante ruta estable, perfil prod, archivo externo obligatorio, reinicio ante fallo cada 10 segundos con límite de 5 intentos/120 segundos, journald y permisos restringidos. No root, no apertura de 8081 pública. Confirmar versión de systemd y Java antes de instalar; no se asume nombre de servicio PostgreSQL ni se alteran dependencias del ERP.

Arranque normal `prod` **no crea administrador**, aunque haya variables bootstrap por error. La unidad elimina dichas variables. Para crear el primero se requiere una ejecución manual separada, esquema preparado y tabla vacía. El proceso no abre HTTP, no ejecuta Flyway, guarda BCrypt cost 12 bajo bloqueo de tabla, termina y no añade/reemplaza usuarios existentes. La API normal debería estar detenida durante este paso.

Futuro comando Linux, desde una terminal autorizada, después de instalar Java/JAR/scripts y preparar entorno/esquema:

```bash
sudo systemd-run --unit=miqa-store-admin-bootstrap --wait --collect --pty \
  --property=User=miqa-store --property=Group=miqa-store \
  --property=WorkingDirectory=/opt/miqa-store/app \
  --property=EnvironmentFile=/etc/miqa-store/miqa-store.env \
  /bin/bash /opt/miqa-store/scripts/bootstrap-admin.sh
```

El script pide username y password/confirmación ocultas; no coloca password en argumentos ni historial, no escribe archivo de contraseña, desactiva tracing y limpia variables al salir. Ejecuta perfil `prod,admin-bootstrap`, non-web, Flyway deshabilitado. Si falla, devuelve código no cero; no repetir como recuperación de contraseña. No agregarlo a `ExecStartPre`, cron, Flyway ni al arranque habitual. No almacenar un password predeterminado. Si no está disponible `systemd-run --pty`, acordar otro mecanismo de carga segura antes de continuar; no improvisar con export de secretos en history.

## Reverse proxy, TLS y cabeceras

Referencia `nginx/api-store.conf.example`: host exacto, TLS 443 con rutas de certificado PLACEHOLDER, upstream `127.0.0.1:8081`, límite request 10 MiB (no habilita upload), timeouts básicos. Host/X-Real-IP/X-Forwarded-* se fijan desde datos del proxy; Forwarded y prefijo recibido se eliminan para evitar suplantación. Spring usa `server.forward-headers-strategy=framework`. No confiar en cabeceras de clientes arbitrarios: el puerto de la app es loopback y el proxy debe sobrescribirlas.

Con Cloudflare, `$remote_addr` será normalmente una IP de Cloudflare hasta configurar real-IP confiable. No confiar todavía en `CF-Connecting-IP`/XFF: identificar rangos oficiales y restricciones del origen al inspeccionar infraestructura. TLS válido en origen y modo Full (strict) serán necesarios; no Flexible. Este ejemplo asume terminación TLS en este proxy; adaptarlo si la infraestructura real es distinta, sin alterar hosts existentes.

Solo `/api/` se publica. `/actuator` se bloquea en el proxy y `/media` no está habilitado. Revisar el formato de logs existente: no incluir Authorization, cookies, cuerpos o tokens; nunca enviar JWT en query string. CORS se aplica en Spring, sin duplicarlo en Nginx. Bearer permite Authorization; origins exactos separados por coma, sin wildcard, sin cookies/credentials. Origen local permanece en perfil local y está prohibido en prod.

Login ya tiene un límite en memoria por usuario heredado. Se conserva, pero no es protección distribuida y reinicia con el proceso. Para esta primera versión, acordar un límite específico de login en el reverse proxy, considerando IP real confiable y sin bloquear a todos detrás de Cloudflare. Cloudflare puede complementar el límite si el plan lo permite. No se configuró ninguna regla ni se añadió otro rate limiter.

## Health, logs y media

Actuator aporta `GET /actuator/health` agregado, sin detalles/componentes; solo health está habilitado/expuesto, descubrimiento y JMX cerrados. Accesible localmente sin JWT, bloqueado al exterior por el ejemplo de proxy. Incluye conectividad DB; `UP` devuelve 200, degradación puede devolver 503. No publica env/beans/configprops/heapdump. Comprobación futura:

```bash
curl --fail http://127.0.0.1:8081/actuator/health
sudo journalctl -u miqa-store.service --since '10 minutes ago'
```

Producción usa INFO stdout/stderr, sin logging de cuerpos MVC ni binds SQL. Errores inesperados registran solo clase de excepción, sin mensaje/causas que puedan contener SQL/credenciales; cliente recibe error genérico sin stacktrace. No activar DEBUG/TRACE para auth ni registrar JWT/Authorization. Revisar retención/acceso de journald del VPS más adelante; no se modifica aquí. Esta sanitización reduce detalle diagnóstico: investigar fallos con métodos controlados, nunca habilitando capturas de secretos.

`MEDIA_STORAGE_PATH` reserva almacenamiento propio y `MEDIA_BASE_URL` resuelve referencias; no hay upload, controlador `/media`, copia de assets o cambio a ProductImage. **El valor /media del ejemplo produciría URLs no servidas actualmente**, por lo que no es un entorno listo para publicar imágenes. Antes del lanzamiento elegir transición temporal explícita (URLs absolutas a assets públicos autorizados) o implementar entrega de media propia en otro bloque, y actualizar referencias sin reescribir V2. No ampliar este trabajo con upload ni almacenamiento externo.

## Orden futuro de puesta en marcha (NO ejecutado)

1. Inspeccionar VPS/proxy/puertos/servicios, backups y aislamiento; instalar/verificar Java 21 según distribución.
2. Crear rol y DB dedicados con los comandos anteriores, después de confirmar instancia autorizada.
3. Crear usuario Linux de sistema sin login, grupo dedicado y directorios con permisos indicados.
4. Copiar JAR versionado y scripts revisados; crear ruta estable `java21` hacia JDK instalado.
5. Completar archivo de entorno externo con secretos nuevos; resolver entrega de imágenes antes de apertura pública.
6. Revisar/instalar unidad systemd, ejecutar `systemd-analyze verify`, luego `daemon-reload`; no cambiar servicios ERP.
7. Iniciar servicio para aplicar Flyway; comprobar health local, journald y tablas/checksums.
8. Detener MIQA temporalmente, ejecutar bootstrap manual y reiniciar MIQA; comprobar login sin registrar token/password.
9. Inspeccionar y adaptar reverse proxy con certificado válido; validar sintaxis antes de cualquier reload y conservar rollback.
10. Configurar únicamente el nuevo host API en Cloudflare en otro bloque autorizado; no modificar DNS existentes.
11. Comprobar API pública/admin, CORS permitido/rechazado, TLS y rate limiting acordado, health no público e imágenes.
12. Solo después cambiar `STORE_API_CONFIG` del frontend de `http://127.0.0.1:8081` a `https://api-store.solucionesmicaela.com`. Frontend permanece intacto aquí.

## Validación local

Resultado comprobado el 14/09/2026: Java 21, `mvnw.cmd test` y `mvnw.cmd package` **BUILD SUCCESS, 36 tests, 0 fallos/errores/omitidos**. Flyway validó V1/V2/V3 en la base aislada de tests; migraciones sin cambios. Health HTTP `{"status":"UP"}`, endpoints sensibles bloqueados; Bash `-n` correcto. No se probó instalación systemd/Nginx ni TLS o el flujo interactivo Linux completo. No se ejecutó prod contra PostgreSQL de producción ni se generaron secretos reales.

Con Java 21 y variables cargadas mediante el helper existente, ejecutar `./mvnw.cmd test` y `./mvnw.cmd package`. Solo suites sobre `miqa_store_test_db` local aislada; no ejecutar el perfil prod contra la DB real para probar estas plantillas. Pruebas nuevas cubren guardas prod sin conexión, CORS, health sin detalles y creación manual del administrador dentro de transacciones revertidas en tests. Validación sintáctica Bash disponible; systemd, Nginx, TLS y flujo interactivo Linux completo requieren revisión futura en entorno adecuado.

## Decisiones antes del VPS

Distribución/Java/systemd, proxy realmente instalado, TLS/Cloudflare/origin trust, puerto/versión/capacidad PostgreSQL, nombres/permisos de roles, backups y restauración, recursos JVM, media temporal, rate limiting login, custodia/rotación JWT y recuperación de admin. Se mantienen deudas JWT sin revocación por token hasta expirar, localStorage/XSS en frontend, sin recuperación de password, sin paginación ni auditoría/locking optimista. No implementar OAuth, pagos, ERP, upload ni sesiones complejas en esta preparación.

Referencias técnicas revisadas: [Spring Boot 4.0 Actuator](https://docs.spring.io/spring-boot/4.0/reference/actuator/monitoring.html), [health](https://docs.spring.io/spring-boot/4.0/api/rest/actuator/health.html), [systemd.exec](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml).
