# Preparación VPS — NO desplegado

Estado: plantillas preparadas localmente el 14/09/2026. Se incorporó la auditoría del propietario sin conectarse ni modificar el VPS, PostgreSQL de producción, Cloudflare, DNS o ERP. **Los comandos de esta guía son pasos futuros, no ejecutados.** Las secciones históricas del README/contexto anteriores a esta fecha no describen la preparación actual.

## Arquitectura y límites

Frontend existente: `https://store.solucionesmicaela.com`, Angular en Cloudflare Workers/Static Assets. API futura: `https://api-store.solucionesmicaela.com` → Cloudflare → TLS en reverse proxy del VPS → `127.0.0.1:8082` → PostgreSQL local `miqa_store_db`. Media futura también en infraestructura propia. No integrar ni modificar ERP, `gigantografias_db`, sus usuarios, puertos, servicios o DNS.

La auditoría del propietario confirma Nginx 1.28.3 existente. La plantilla requiere decidir TLS MIQA antes de instalarse; no alterar los hosts ERP/LaserMonitor. No hay automatización de deploy.

## Configuración requerida

`application-prod.properties` conserva la convención existente `DB_*`. No mezclar con nombres `STORE_DB_*`. Los valores propios del entorno son obligatorios, sin fallback de desarrollo. Las políticas de seguridad (loopback, validate, Flyway) están declaradas en el perfil.

| Variable | Valor futuro / significado |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod`; systemd también fija el perfil en el comando |
| `DB_HOST` | `127.0.0.1` (solo loopback permitido) |
| `DB_PORT` | `5432`, confirmado en la auditoría |
| `DB_NAME` | `miqa_store_db`; prod rechaza la base de tests y ERP |
| `DB_USERNAME` | miqa_store_app, dedicado MIQA, nunca postgres ni usuario ERP |
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
  backups/                        # root:root 0700; dumps 0600
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

`systemd/miqa-store.service` es una plantilla, no instalada. Usuario/grupo `miqa-store`, Java 21 mediante ruta estable, perfil prod, archivo externo obligatorio, reinicio ante fallo cada 10 segundos con límite de 5 intentos/120 segundos, journald y permisos restringidos. No root, no apertura de 8082 pública. Confirmar versión de systemd y Java antes de instalar; no se asume nombre de servicio PostgreSQL ni se alteran dependencias del ERP.

Arranque normal `prod` **no crea administrador**, aunque haya variables bootstrap por error. La unidad elimina dichas variables. Para crear el primero se requiere una ejecución manual separada, esquema preparado y tabla vacía. El proceso no abre HTTP, no ejecuta Flyway, guarda BCrypt cost 12 bajo bloqueo de tabla, termina y no añade/reemplaza usuarios existentes. La API normal debería estar detenida durante este paso.

Futuro comando Linux, desde una terminal autorizada, después de verificar Java global e instalar JAR/scripts y preparar entorno/esquema:

```bash
sudo systemd-run --unit=miqa-store-admin-bootstrap --wait --collect --pty \
  --property=User=miqa-store --property=Group=miqa-store \
  --property=WorkingDirectory=/opt/miqa-store/app \
  --property=EnvironmentFile=/etc/miqa-store/miqa-store.env \
  /bin/bash /opt/miqa-store/scripts/bootstrap-admin.sh
```

El script pide username y password/confirmación ocultas; no coloca password en argumentos ni historial, no escribe archivo de contraseña, desactiva tracing y limpia variables al salir. Ejecuta perfil `prod,admin-bootstrap`, non-web, Flyway deshabilitado. Si falla, devuelve código no cero; no repetir como recuperación de contraseña. No agregarlo a `ExecStartPre`, cron, Flyway ni al arranque habitual. No almacenar un password predeterminado. Si no está disponible `systemd-run --pty`, acordar otro mecanismo de carga segura antes de continuar; no improvisar con export de secretos en history.

## Reverse proxy, TLS y cabeceras

Referencia `nginx/api-store.conf.example`: host exacto, TLS 443 con rutas de certificado PLACEHOLDER, upstream `127.0.0.1:8082`, límite request 10 MiB (no habilita upload), timeouts básicos. Host/X-Real-IP/X-Forwarded-* se fijan desde datos del proxy; Forwarded y prefijo recibido se eliminan para evitar suplantación. Spring usa `server.forward-headers-strategy=framework`. No confiar en cabeceras de clientes arbitrarios: el puerto de la app es loopback y el proxy debe sobrescribirlas.

Con Cloudflare, `$remote_addr` será normalmente una IP de Cloudflare hasta configurar real-IP confiable. No confiar todavía en `CF-Connecting-IP`/XFF: identificar rangos oficiales y restricciones del origen al inspeccionar infraestructura. TLS válido en origen y modo Full (strict) serán necesarios; no Flexible. Este ejemplo asume terminación TLS en este proxy; adaptarlo si la infraestructura real es distinta, sin alterar hosts existentes.

Solo `/api/` se publica. `/actuator` se bloquea en el proxy y `/media` no está habilitado. Revisar el formato de logs existente: no incluir Authorization, cookies, cuerpos o tokens; nunca enviar JWT en query string. CORS se aplica en Spring, sin duplicarlo en Nginx. Bearer permite Authorization; origins exactos separados por coma, sin wildcard, sin cookies/credentials. Origen local permanece en perfil local y está prohibido en prod.

Login ya tiene un límite en memoria por usuario heredado. Se conserva, pero no es protección distribuida y reinicia con el proceso. Para esta primera versión, acordar un límite específico de login en el reverse proxy, considerando IP real confiable y sin bloquear a todos detrás de Cloudflare. Cloudflare puede complementar el límite si el plan lo permite. No se configuró ninguna regla ni se añadió otro rate limiter.

## Health, logs y media

Actuator aporta `GET /actuator/health` agregado, sin detalles/componentes; solo health está habilitado/expuesto, descubrimiento y JMX cerrados. Accesible localmente sin JWT, bloqueado al exterior por el ejemplo de proxy. Incluye conectividad DB; `UP` devuelve 200, degradación puede devolver 503. No publica env/beans/configprops/heapdump. Comprobación futura:

```bash
curl --fail http://127.0.0.1:8082/actuator/health
sudo journalctl -u miqa-store.service --since '10 minutes ago'
```

Producción usa INFO stdout/stderr, sin logging de cuerpos MVC ni binds SQL. Errores inesperados registran solo clase de excepción, sin mensaje/causas que puedan contener SQL/credenciales; cliente recibe error genérico sin stacktrace. No activar DEBUG/TRACE para auth ni registrar JWT/Authorization. Revisar retención/acceso de journald del VPS más adelante; no se modifica aquí. Esta sanitización reduce detalle diagnóstico: investigar fallos con métodos controlados, nunca habilitando capturas de secretos.

`MEDIA_STORAGE_PATH` reserva almacenamiento propio y `MEDIA_BASE_URL` resuelve referencias; no hay upload, controlador `/media`, copia de assets o cambio a ProductImage. **El valor /media del ejemplo produciría URLs no servidas actualmente**, por lo que no es un entorno listo para publicar imágenes. Antes del lanzamiento elegir transición temporal explícita (URLs absolutas a assets públicos autorizados) o implementar entrega de media propia en otro bloque, y actualizar referencias sin reescribir V2. No ampliar este trabajo con upload ni almacenamiento externo.

## Orden futuro de puesta en marcha (NO ejecutado)

1. Confirmar que no cambió la infraestructura auditada, backups y aislamiento; verificar Java 21 global /usr/bin/java.
2. Crear rol y DB dedicados con los comandos anteriores, después de confirmar instancia autorizada.
3. Crear usuario Linux de sistema sin login, grupo dedicado y directorios con permisos indicados.
4. Copiar JAR versionado y scripts revisados; usar /usr/bin/java existente, sin JVM privada.
5. Completar archivo de entorno externo con secretos nuevos; resolver entrega de imágenes antes de apertura pública.
6. Revisar/instalar unidad systemd, ejecutar `systemd-analyze verify`, luego `daemon-reload`; no cambiar servicios ERP.
7. Iniciar servicio para aplicar Flyway; comprobar health local, journald y tablas/checksums.
8. Detener MIQA temporalmente, ejecutar bootstrap manual y reiniciar MIQA; comprobar login sin registrar token/password.
9. Inspeccionar y adaptar reverse proxy con certificado válido; validar sintaxis antes de cualquier reload y conservar rollback.
10. Configurar únicamente el nuevo host API en Cloudflare en otro bloque autorizado; no modificar DNS existentes.
11. Comprobar API pública/admin, CORS permitido/rechazado, TLS y rate limiting acordado, health no público e imágenes.
12. Verificar backup MIQA y restauración, habilitar timer y resolver copia externa/alertas antes de dar por terminado el despliegue.
13. Solo después cambiar `STORE_API_CONFIG` del frontend de `http://127.0.0.1:8081` a `https://api-store.solucionesmicaela.com`. Frontend permanece intacto aquí.

## Validación local

Resultado comprobado el 14/09/2026: Java 21, `mvnw.cmd test` y `mvnw.cmd package` **BUILD SUCCESS, 37 tests, 0 fallos/errores/omitidos**. Flyway validó V1/V2/V3 en la base aislada de tests; migraciones sin cambios. Health HTTP `{"status":"UP"}`, endpoints sensibles bloqueados; Bash `-n` correcto. No se probó instalación systemd/Nginx ni TLS o el flujo interactivo Linux completo. No se ejecutó prod contra PostgreSQL de producción ni se generaron secretos reales.

Con Java 21 y variables cargadas mediante el helper existente, ejecutar `./mvnw.cmd test` y `./mvnw.cmd package`. Solo suites sobre `miqa_store_test_db` local aislada; no ejecutar el perfil prod contra la DB real para probar estas plantillas. Pruebas nuevas cubren guardas prod sin conexión, CORS, health sin detalles y creación manual del administrador dentro de transacciones revertidas en tests. Validación sintáctica Bash disponible; systemd, Nginx, TLS y flujo interactivo Linux completo requieren revisión futura en entorno adecuado.

## Pendientes antes de desplegar

Validación systemd, TLS/Cloudflare/origin trust, permisos efectivos de roles, ensayo de backups/restauración, medición de memoria total JVM, media temporal, rate limiting login, custodia/rotación JWT y recuperación de admin. Se mantienen deudas JWT sin revocación por token hasta expirar, localStorage/XSS en frontend, sin recuperación de password, sin paginación ni auditoría/locking optimista. No implementar OAuth, pagos, ERP, upload ni sesiones complejas en esta preparación.

Referencias técnicas revisadas: [Spring Boot 4.0 Actuator](https://docs.spring.io/spring-boot/4.0/reference/actuator/monitoring.html), [health](https://docs.spring.io/spring-boot/4.0/api/rest/actuator/health.html), [systemd.exec](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml).

## Auditoría VPS incorporada — 14/09/2026

Ubuntu 26.04 LTS x86_64; 1 vCPU, 1.6 GiB RAM, 4.8 GiB swap, 29 GB libres. OpenJDK 21.0.12 global /usr/bin/java. PostgreSQL 18.6 solo localhost:5432, max_connections=100, TCP scram-sha-256. UFW incoming deny: NO abrir 8082 ni 5432 ni cambiar SSH 22/2222.

ERP gigantografias.service (api.solucionesmicaela.com) ocupa 8080; LaserMonitor lasermonitor-backend.service (laser-api.solucionesmicaela.com) ocupa 8081. No tocar ninguno. MIQA prod usa 127.0.0.1:8082; local conserva 8081. Frontend permanece en Cloudflare Workers Static Assets, fuera del VPS.

Servicio y bootstrap usan /usr/bin/java con -Xms64m -Xmx192m, sin JVM privada ni ajustes GC adicionales. El heap no limita RSS total: metaspace, threads y memoria nativa requieren margen. Medir carga real con las otras apps antes de publicar; swap no reemplaza RAM. Hikari conserva 5/1.

TLS MIQA pendiente: ERP usa Cloudflare Origin Certificate; LaserMonitor Let's Encrypt. Los CHANGE_ME de Nginx son solo marcadores, no certificados definitivos. No instalar el vhost antes de decidir TLS. Cloudflare y confianza de IP se configurarán después.

## Backup MIQA preparado, NO ejecutado

Script deploy/scripts/backup-miqa-store.sh y unidades miqa-store-backup.service/.timer. pg_dump 18 (-Fc) exclusivamente miqa_store_db/miqa_store_app en 127.0.0.1:5432. Timestamp UTC, umask 077, directorio /opt/miqa-store/backups root:root 0700 y archivos 0600. Archivo parcial, lock, validación pg_restore --list y publicación tras éxito. Retención 14 días limitada al prefijo MIQA, solo después de dump exitoso y legible. Fallos explícitos conservan backups anteriores; se elimina solo el parcial.

El oneshot root sin capabilities escribe únicamente backups y usa el rol SQL miqa_store_app; así la API no puede borrar los dumps. LoadCredential entrega /etc/miqa-store/backup.pgpass (root:root 0600) como credencial privada; PGPASSFILE la consume sin contraseña en argumentos ni JWT. No source del env Spring. Introducir en editor una entrada host:port:database:user:password para 127.0.0.1:5432:miqa_store_db:miqa_store_app, sin comodines. Escapar ':' y '\' con '\'. Rotar este archivo junto con DB_PASSWORD. pg_dump stderr se oculta para evitar contenido sensible; diagnosticar fallos de forma controlada.

Timer diario 03:00 UTC con hasta 15 minutos de retraso aleatorio y Persistent=true. Habilitar solo después de verificar backup y ensayo de restauración. No se ejecutó dump ni se creó timer real. pg_restore --list no reemplaza una restauración completa.

Comandos posteriores propuestos; asumir release revisada en /tmp/miqa-store-release, confirmar nombres libres y checksums antes de copiar. No repetir creación ni sobrescribir configuración existente. Crear rol/base con el SQL de arriba mediante sudo -u postgres psql -p 5432 -d postgres.

```bash
/usr/bin/java -version
/usr/lib/postgresql/18/bin/pg_dump --version
sudo ss -ltnp
sudo useradd --system --user-group --home-dir /opt/miqa-store --shell /usr/sbin/nologin miqa-store
sudo install -d -o root -g miqa-store -m 0750 /opt/miqa-store /opt/miqa-store/app /opt/miqa-store/scripts
sudo install -d -o miqa-store -g miqa-store -m 0750 /opt/miqa-store/media
sudo install -d -o root -g root -m 0700 /opt/miqa-store/backups /etc/miqa-store
cd /tmp/miqa-store-release
sha256sum target/miqa-store-backend-0.0.1-SNAPSHOT.jar
sudo install -o root -g miqa-store -m 0640 target/miqa-store-backend-0.0.1-SNAPSHOT.jar /opt/miqa-store/app/miqa-store-backend.jar
sudo install -o root -g miqa-store -m 0750 scripts/bootstrap-admin.sh deploy/scripts/backup-miqa-store.sh /opt/miqa-store/scripts/
sudo install -o root -g root -m 0600 deploy/env/miqa-store.env.example /etc/miqa-store/miqa-store.env
sudo nano /etc/miqa-store/miqa-store.env
sudo install -o root -g root -m 0600 /dev/null /etc/miqa-store/backup.pgpass
sudo nano /etc/miqa-store/backup.pgpass
sudo install -o root -g root -m 0644 deploy/systemd/miqa-store.service deploy/systemd/miqa-store-backup.service deploy/systemd/miqa-store-backup.timer /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/miqa-store.service /etc/systemd/system/miqa-store-backup.service /etc/systemd/system/miqa-store-backup.timer
sudo systemctl daemon-reload
sudo systemctl start miqa-store.service
curl --fail http://127.0.0.1:8082/actuator/health
# Detener solo MIQA, ejecutar el bootstrap interactivo descrito arriba y reiniciar MIQA.
sudo systemctl start miqa-store-backup.service
```

Ensayo futuro en base VACÍA MIQA de recuperación, nunca ERP ni la base activa. No es fixture de tests. Sustituir TIMESTAMP por el dump elegido; se conecta por socket como postgres y restaura con rol miqa_store_app. No --clean.

```bash
set -euo pipefail
sudo -u postgres createdb -p 5432 --owner=miqa_store_app --template=template0 miqa_store_restore_check
sudo /usr/lib/postgresql/18/bin/pg_restore --list /opt/miqa-store/backups/miqa_store_db_TIMESTAMP.dump
sudo cat /opt/miqa-store/backups/miqa_store_db_TIMESTAMP.dump | sudo -u postgres /usr/lib/postgresql/18/bin/pg_restore --exit-on-error --single-transaction --no-owner --role=miqa_store_app --port=5432 --dbname=miqa_store_restore_check
sudo -u postgres psql -p 5432 -d miqa_store_restore_check -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;'
sudo -u postgres psql -p 5432 -d miqa_store_restore_check -c 'SELECT count(*) FROM products;'
# Tras revisar restauración y datos:
sudo systemctl enable --now miqa-store-backup.timer
sudo systemctl list-timers miqa-store-backup.timer
sudo systemctl enable miqa-store.service
```

La base de ensayo queda para revisión y eliminación posterior explícita. Recuperación definitiva exige interrupción planificada solo MIQA y destino vacío. pg_dump no incluye roles globales, env/JWT, JAR ni media; custodiar estos elementos por canales seguros. Backup local no protege pérdida del VPS: copia cifrada externa bajo control propio, alertas de fallo/espacio y respaldo media quedan pendientes antes de cerrar despliegue.

Nginx: completar primero un archivo api-store.conf.reviewed con TLS real, comprobar que sites-enabled se incluye y destinos libres. Solo entonces:
```bash
sudo install -o root -g root -m 0644 /tmp/miqa-store-release/api-store.conf.reviewed /etc/nginx/sites-available/api-store.conf
sudo ln -s /etc/nginx/sites-available/api-store.conf /etc/nginx/sites-enabled/api-store.conf
sudo nginx -t && sudo systemctl reload nginx
```
No comandos de UFW/SSH/Cloudflare. No alterar vhosts existentes. Referencia de formato y restauración: [pg_dump 18](https://www.postgresql.org/docs/18/app-pgdump.html), [pgpass](https://www.postgresql.org/docs/18/libpq-pgpass.html), [systemd timer](https://github.com/systemd/systemd/blob/main/man/systemd.timer.xml).
