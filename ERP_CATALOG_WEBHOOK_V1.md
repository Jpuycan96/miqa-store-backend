# ERP -> MIQA: receptor de eventos de catalogo, etapa 1

Implementado exclusivamente en MIQA backend. Recepcion y procesamiento deshabilitados
por defecto. No incluye emisor ERP, activacion de produccion ni cambios en el frontend.
El endpoint administrativo de sincronizacion sigue disponible.

## Contrato HTTP definitivo

`POST /api/integracion/erp/v1/catalogo/eventos`

Cabeceras obligatorias:

```text
Content-Type: application/json
X-MIQA-Webhook-Timestamp: <segundos Unix decimales>
X-MIQA-Webhook-Signature: v1=<HMAC-SHA256 hexadecimal minusculo de 64 caracteres>
```

Se permite `application/json; charset=utf-8`. Cuerpo UTF-8, sin BOM, maximo **4096 bytes**.
No se admite Content-Encoding, query string, cabeceras de firma/timestamp repetidas,
tokens JWT administrativos ni X-ERP-Service-Key como autenticacion alternativa.
La ruta y sus subrutas estan aisladas en una cadena Spring Security de mayor prioridad
que la exportacion existente; solamente el POST exacto ejecuta la recepcion.
Usar HTTPS en el futuro emisor y preservar los bytes en cualquier proxy.

El objeto tiene exactamente estos cuatro campos, sin campos adicionales o duplicados:

```json
{"eventId":"39d45f22-0ea9-4e8f-9c27-a04a76360141","eventType":"ERP_CATALOG_CHANGED","occurredAt":"2026-10-09T12:00:00Z","schemaVersion":1}
```

- `eventId`: UUID canonico en minusculas, unico por cambio/notificacion.
- `eventType`: exclusivamente `ERP_CATALOG_CHANGED`.
- `occurredAt`: instante UTC `YYYY-MM-DDTHH:mm:ss[.ffffff]Z`; fraccion opcional de
  1 a 6 digitos, no anterior a Unix epoch ni mas de 300 s en el futuro. Puede ser
  antiguo: un outbox debe poder entregar cambios pendientes tras una caida prolongada.
- `schemaVersion`: numero entero JSON `1`, no texto, decimal ni otra version.

No se reciben nombres, precios, servicios, materiales o datos editoriales. El evento
solo solicita una reconciliacion completa usando el ERP como fuente de verdad.

## Firma y replay

El secreto especifico de webhook tiene 32-256 caracteres ASCII imprimibles sin espacios.
Debe generarse aleatoriamente, guardarse fuera del repositorio y ser independiente de
la clave ERP saliente y de MIQA_ERP_REQUESTS_API_KEY. No se decodifica como Base64/hex:
los bytes del secreto son sus caracteres UTF-8 exactos.

```text
mensaje = ASCII(valor exacto X-MIQA-Webhook-Timestamp) || byte 0x0A || cuerpo original
firma = "v1=" || hex_minusculo(HMAC_SHA256(UTF8(secreto), mensaje))
```

El timestamp es un entero decimal positivo, sin espacios ni ceros iniciales, hasta
12 digitos. Debe estar dentro de +/-300 s del reloj MIQA por defecto; ambos servidores
requieren relojes sincronizados. La comparacion de la firma usa MessageDigest.isEqual.
No se reserializa el JSON antes de autenticarlo.

El emisor conserva **el mismo eventId y exactamente los mismos bytes del cuerpo** en
todos los intentos. Renueva el timestamp HTTP y la firma en cada entrega/reintento;
occurredAt permanece estable. Variar espacios u orden de campos con un eventId ya
aceptado produce 409. El hash SHA-256 persistido del cuerpo y la PK del evento protegen
replays incluso despues del procesamiento. No se guarda el cuerpo ni la firma.
No borrar registros de deduplicacion hasta definir un horizonte de retencion compatible
con el outbox; esta etapa no los purga automaticamente.

## Respuestas

Primera recepcion, **solo despues del commit**:

```text
202 Accepted
{"schemaVersion":1,"eventId":"39d45f22-0ea9-4e8f-9c27-a04a76360141","status":"ACCEPTED"}
```

Entrega repetida identica, sin volver a encolar ni cambiar estado/fechas/intentos:

```text
200 OK
{"schemaVersion":1,"eventId":"39d45f22-0ea9-4e8f-9c27-a04a76360141","status":"DUPLICATE"}
```

Estas respuestas confirman recepcion durable, **no** sincronizacion terminada.
Todas las respuestas de la ruta llevan Cache-Control: no-store.

| HTTP | Codigo | Comportamiento del futuro emisor |
| --- | --- | --- |
| 400 | INVALID_EVENT | Corregir contrato/transporte; no repetir agresivamente |
| 401 | UNAUTHENTICATED | Revisar secreto, firma y reloj; no repetir agresivamente |
| 404 | NOT_FOUND | Receptor deshabilitado o subruta inexistente |
| 405 | METHOD_NOT_ALLOWED | Usar POST en la ruta exacta |
| 409 | EVENT_ID_CONFLICT | El mismo identificador tiene otro cuerpo; diagnosticar |
| 413 | PAYLOAD_TOO_LARGE | Reducir el cuerpo al contrato de cuatro campos |
| 503 | RECEIPT_UNAVAILABLE | Persistencia/commit fallo; conservar evento y reintentar |

Ante timeout, respuesta perdida o cualquier 5xx, conservar el evento y reenviar con
backoff. Un commit puede haberse realizado aunque la respuesta no llegue: el siguiente
intento obtiene DUPLICATE. Mensajes de error genericos, sin claves, cuerpo o detalles SQL.

## Persistencia y trabajador

V11 crea `erp_catalog_webhook_events`, con PK event_id, restricciones de contrato,
estados coherentes e indices parciales para vencimientos, orden de pendientes y tokens
de procesamiento. Guarda identificadores/fechas, hash del cuerpo, estado, numero de
intentos, proximo intento, token del lote y ultimo resultado seguro. No almacena datos
comerciales, contenido editorial, credenciales ni mensajes de excepciones.

`ErpWebhookQueue.accept()` inserta en una transaccion REQUIRES_NEW independiente,
con ON CONFLICT DO NOTHING. El receptor retorna despues del commit y nunca consulta
al ERP dentro de la solicitud HTTP. Si la tabla no existe o hay fallo de persistencia,
no confirma el evento.

```text
PENDING -> PROCESSING -> PROCESSED
                    -> RETRY -> PROCESSING
                    -> FAILED
PROCESSING abandonado -> RETRY (WORKER_RECOVERED)
```

El scheduler se registra solo con procesamiento habilitado y fuera de admin-bootstrap.
Cada tick consulta **la cola PostgreSQL local**. Cola vacia/no vencida: cero llamadas
al ERP. Eventos cercanos esperan inicialmente 3 s y se agrupan hasta 200 por lote.
Una notificacion vencida puede agrupar otras ya recibidas aun dentro de la ventana
inicial; los eventos que llegan despues del claim no se reconocen por el lote en curso.

Coordinacion entre instancias:

1. Conexion dedicada obtiene `pg_try_advisory_lock(724193820128)`, sin espera bloqueante.
2. Recupera PROCESSING dejado por sesiones anteriores, con backoff inicial.
3. Claim transaccional corto asigna un UUID de lote; todas las escrituras posteriores
   exigen ese token y PROCESSING, para evitar reconocimiento por un trabajador antiguo.
4. Invoca exactamente `ErpCatalogService.synchronize()` sin modificarlo. Este conserva
   `pg_try_advisory_xact_lock(724193820126)` y su transaccion comercial atomica: tambien
   se coordina con sync/binding manual. El bloqueo editorial 724193820127 no cambia.
5. Persiste el resultado y libera explicitamente el bloqueo de sesion en finally, antes
   de devolver la conexion. Si adquirir/liberar deja un estado incierto, aborta la conexion.

No hay lease que venza durante una llamada ERP. Una sesion muerta libera su advisory
lock; otra instancia recupera las filas. Se necesitan **al menos dos conexiones** por
instancia en el pool (una para el lock y otra para transacciones/sync), mas capacidad
para trafico publico. application-prod ya declara maximum-pool-size=5; no se modifica.
Requiere conexiones PostgreSQL de sesion estables: no usar PgBouncer en modo transaction
pooling para esta conexion de lock.

El bloqueo no se conserva durante coalescing, backoff ni entre ticks. Si sync manual
esta ocupado, se registra SYNC_BUSY y se reintenta despues, sin consultar ERP.

## Fallos y limites de reintento

- SUCCESS: PROCESSED; conserva el estado de sincronizacion ya existente.
- ERP_ERROR, SYNC_BUSY o excepcion inesperada: RETRY con ultimo resultado seguro.
- NOT_CONFIGURED / INVALID_CONTRACT: FAILED, conservado para diagnostico. No hay
  reintento automatico de estos errores permanentes. Tras corregir su causa se necesita
  una nueva notificacion con otro eventId o una recuperacion operativa controlada;
  su mecanismo de reencolado queda por definir, sin SQL manual en produccion en esta etapa.
- Fallo al guardar resultado: PROCESSING sigue durable y se recupera al siguiente
  trabajador. Si la BD sigue caida, permanece pendiente en la BD hasta restablecerla.

Backoff exponencial por intento: 30, 60, 120, 240, 480 y 900 s por defecto; despues
900 s. BACKOFF_STEPS limita escalones exponenciales; MAX_BACKOFF_SECONDS limita demora.
Los fallos temporales no se descartan por alcanzar un numero finito de intentos: se
reintentan indefinidamente a la demora acotada para recuperar caidas prolongadas.
Un RETRY futuro bloquea tambien las nuevas notificaciones hasta su vencimiento, porque
todas solicitan la misma reconciliacion; un flujo continuo de eventos no evade el backoff.
Cada tick procesa como maximo un lote, sin bucles ni dormir con bloqueos adquiridos.

Garantia **al menos una vez**, no exactamente una vez: si sync termina y MIQA cae antes
del reconocimiento del lote, la reconciliacion se repite. La PK evita nuevas entradas
duplicadas y el sincronizador existente es idempotente. En una particion que pierde
sesiones PostgreSQL puede haber lecturas ERP repetidas; las transacciones y tokens
protegen la persistencia, sin afirmar exclusividad de una operacion HTTP remota despues
de perder la sesion. No existe ACK remoto ni historial completo de cada intento: se
conservan contador, estado y ultimo resultado, junto a logs sanitizados de lote.

## Configuracion futura: sin valores reales

| Variable de entorno | Default | Limite/uso |
| --- | --- | --- |
| MIQA_ERP_WEBHOOK_RECEIVE_ENABLED | false | Habilita solo la recepcion |
| MIQA_ERP_WEBHOOK_PROCESSING_ENABLED | false | Habilita trabajador/scheduler local |
| MIQA_ERP_WEBHOOK_SECRET | vacio | Secreto HMAC especifico; requerido al recibir |
| MIQA_ERP_WEBHOOK_TIMESTAMP_TOLERANCE_SECONDS | 300 | 1-900 s |
| MIQA_ERP_WEBHOOK_QUEUE_INTERVAL_MS | 5000 | 1000-3600000 ms, fixed delay |
| MIQA_ERP_WEBHOOK_COALESCE_SECONDS | 3 | 0-60 s |
| MIQA_ERP_WEBHOOK_BATCH_SIZE | 200 | 1-1000 eventos por reconciliacion |
| MIQA_ERP_WEBHOOK_INITIAL_BACKOFF_SECONDS | 30 | >=1, <= MAX_BACKOFF_SECONDS |
| MIQA_ERP_WEBHOOK_MAX_BACKOFF_SECONDS | 900 | Hasta 86400 s |
| MIQA_ERP_WEBHOOK_BACKOFF_STEPS | 6 | 1-20 escalones; no descarta fallos temporales |

Se reutilizan ERP_TIENDA_VIRTUAL_BASE_URL y ERP_TIENDA_VIRTUAL_API_KEY del sincronizador,
sin cambiar endpoint, contratos publicos ni evaluacion de precios. Se puede detener
recepcion y mantener drenaje, o detener procesamiento y mantener recepcion durable.
Una configuracion invalida falla al arrancar con mensaje generico, sin imprimir secreto.

## Conservacion comercial y editorial

No se modifican ErpCatalogService, Repository, Client, EditorialCatalog ni publicacion.
Se conservan erpServiceId, elegibilidad/disponibilidad, revision y configurationVersion,
categorias ERP, vinculos canonicos y configuracion de materiales/modelos. Productos
nuevos siguen en borrador. Las fichas existentes conservan publicacion, imagenes,
galerias, descripciones, SEO y slugs. Una baja no elimina esos datos; pricing sigue
exclusivamente en ERP. La regresion ejecuta las pruebas existentes de estas reglas.

## Validacion y siguientes etapas

Las pruebas de unidad/HTTP arrancan MVC y las cadenas reales sin Boot/DataSource/Flyway:
firma, reloj/replay, limites, contrato estricto, errores DB/commit, aislamiento de claves,
recepcion deshabilitada, commit antes del retorno, agrupacion, llegada durante sync,
backoff, recuperacion, concurrencia simulada, cola vacia y perfil de bootstrap.

`ErpWebhookPersistenceIT` queda opt-in y **no ejecutado en esta etapa**. Requiere que el
propietario aplique V11 previamente en miqa_store_test_db aislada, cola vacia y las
variables RUN_ERP_WEBHOOK_PERSISTENCE_IT=true / TEST_DB_PASSWORD. Usa destino TEST fijo,
sin Boot/Flyway/DDL, commits reales y limpieza solo de sus UUID sinteticos. Cubre
visibilidad durable entre conexiones, entregas concurrentes, constraints/rollback,
SQL de lotes/recovery/tokens/backoff y advisory locks reales. Ninguna prueba actual
ha conectado a BD o ERP, ni aplicado migraciones; la validacion SQL real esta pendiente.

Validacion local offline (POM temporal ignorado equivalente al versionado, salida
aislada del editor; Maven instalado se usa porque el Wrapper local no inicia):

```powershell
$env:JAVA_HOME = (Resolve-Path '.tmp/jdk21/jdk-21.0.12.1+1').Path
& 'C:/apache-maven-3.9.9/bin/mvn.cmd' -o '-Dmaven.repo.local=C:/Users/Jhairth Manuel/.m2/repository' -f .tmp/webhook-build/pom.xml '-Dtest=ErpWebhookSecurityTest,ErpWebhookQueueTest,ErpWebhookWorkerTest,ErpWebhookConfigurationTest,ErpCatalogTest,ErpEditorialCatalogTest,ErpConfigurationVersionRegressionTest,PublicErpConfigurationTest,ErpCatalogSecurityTest,RequestExportSecurityTest,RequestExportContextTest' package
git diff --check
```

Resultado comprobado: pasada focalizada/regresion de 62 pruebas sin fallos/errores y
package BUILD SUCCESS. Despues de agregar el caso de JWT administrativo valido se
repitieron las 9 pruebas HTTP afectadas y package, tambien correctos. Reportes finales:
63 pruebas distintas aprobadas (27 nuevas y 36 existentes), ninguna omitida; todas las
pruebas IT compiladas. JAR en `.tmp/webhook-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar`.
`git diff --check` correcto. No ejecucion PostgreSQL/Flyway/ERP.

Antes de habilitar en un despliegue posterior: validar V11/IT en TEST, dimensionar pool,
comprobar proxy HTTPS/reloj y preparar monitoreo de RETRY/FAILED y antiguedad de la cola.
La migracion se ejecutaria por Flyway en el arranque normal aunque flags esten false;
**los flags no deshabilitan Flyway**. Este trabajo solo crea V11, sin aplicarla.

Etapa emisora ERP: outbox transaccional durable en todos los caminos que alteren
elegibilidad/categoria/configuracion/materiales/modelos relevantes; evento emitido
despues de commit, identidad/cuerpo inmutables, firma nueva por intento, backoff y ACK
202/200 durable. Acordar rotacion de secreto (esta version acepta uno), retencion,
recuperacion operativa de FAILED, metricas/alertas y reconciliacion de cambios historicos.
El backend MIQA actual no consulta ERP periodicamente. Eliminar el boton del frontend
solo despues de validar emisor y receptor funcionando sin administrador abierto.
