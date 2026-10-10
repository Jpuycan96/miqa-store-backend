# Recuperación administrativa de eventos ERP

Implementación local; no habilita webhooks ni aplica migraciones. El contrato HTTP/HMAC
de recepción v1 y `ErpCatalogService.synchronize()` se mantienen intactos.

## Deadline del catálogo

`ErpCatalogClient.fetchAvailable()` espera como máximo 20 segundos por la respuesta
completa, incluyendo conexión, headers y cuerpo. El plazo es total, no se reinicia al
recibir fragmentos. Mantiene el connect timeout de 5 segundos y el límite de 10 MiB.
Timeout/interrupción cancelan el intercambio; una interrupción conserva su indicador.
El resultado es `ERP_ERROR`, sin causa, cuerpo ni credenciales expuestas. El worker
reintenta con el backoff existente, sin reconciliar un catálogo parcial como vacío.
La liberación de locks sigue los `finally` y transacciones existentes. El deadline
HTTP no sustituye los timeouts JDBC o la vigilancia de conexiones de la infraestructura.

## Preparación requerida

Aplicar V12 por Flyway en un entorno autorizado antes de utilizar el reencolado.
V1–V11 no se modifican. V12 crea `erp_catalog_webhook_requeues` y los índices de
auditoría/consulta FAILED; no modifica productos, publicaciones ni precios.
En esta tarea V12 **no se aplica** y ninguna prueba se conecta a PostgreSQL.

Las rutas usan exclusivamente el JWT administrativo existente: issuer, audience,
firma, expiración y administrador activo. La identidad auditada viene del `sub`
validado; no se acepta una identidad proporcionada en el cuerpo. Las credenciales
técnicas ERP/HMAC no autorizan estas operaciones. Sin cambios frontend.

## Procedimiento operativo

1. Consultar `GET /api/admin/erp-catalog/webhook-events/failed?limit=50` con Bearer
   administrativo. `limit` admite 1–100. Devuelve eventId, receivedAt, attempts y
   lastOutcome; no cuerpos, claves, firmas ni datos comerciales. Sin polling.
2. Corregir la causa (`NOT_CONFIGURED` o `INVALID_CONTRACT`) antes de recuperar.
3. Solicitar `POST /api/admin/erp-catalog/webhook-events/{eventId}/requeue` con el
   mismo Bearer y JSON:

   ```json
   {
     "requestId": "1f2a2c25-291a-487b-ae4d-c2bdc6dbecfa",
     "reason": "Contrato ERP corregido; referencia operativa INC-123"
   }
   ```

   Generar un UUID nuevo por operación; motivo obligatorio, 8–500 caracteres de texto
   sin controles. No incluir secretos o información personal en el motivo.
4. HTTP 202 devuelve requestId, eventId, requeuedAt, nextAttemptAt y status REQUEUED.
   Significa recuperación guardada, no sincronización completada. Ambas rutas exitosas
   incluyen `Cache-Control: no-store`.
5. El evento original pasa FAILED → PENDING y espera la ventana de agrupación.
   El worker existente debe estar habilitado para procesarlo; se conserva el cooldown
   global RETRY. Consultar el estado de sincronización existente
   `GET /api/admin/erp-catalog/sync` y revisar FAILED bajo demanda después de procesar.

HTTP 401 para ausencia/token inválido/administrador inactivo; 400 para entrada inválida;
404 si el evento no existe; 409 para estado distinto de FAILED o requestId reutilizado
con otro evento/administrador/motivo. Un fallo de persistencia no confirma la recuperación.
Si la respuesta se pierde, repetir **el mismo** requestId, evento y motivo con el mismo
administrador devuelve el recibo original y no reencola, incluso después de procesado
o de un nuevo fallo. Un nuevo FAILED requiere diagnóstico y un **nuevo** requestId.

## Atomicidad y trazabilidad

Una transacción corta independiente bloquea la fila original, comprueba estado e
idempotencia, inserta auditoría y actualiza la cola antes de confirmar 202. Dos pedidos
concurrentes para el mismo evento no realizan dos recuperaciones. La PK requestId
protege también reutilización concurrente entre eventos diferentes; cualquier conflicto
revierte la operación completa. El worker solo puede reclamar el evento tras el commit.

Se mantienen eventId, hash, fecha original, contador acumulado y último diagnóstico.
La auditoría registra requestId, eventId, adminId, motivo, intentos/resultado previos,
fecha de reencolado y próxima fecha. No se crea otro webhook ni se invoca HTTP ERP desde
el endpoint administrativo. Replays del emisor siguen deduplicándose como antes.
Las escrituras de catálogo siguen usando la reconciliación idempotente existente,
creando borradores y preservando contenido editorial y publicaciones.

La FK de auditoría impide borrar recibos recuperados antes de resolver su auditoría.
No se implementa limpieza automática. Definir retención conjunta y permisos mínimos
del rol de aplicación antes de añadir un proceso futuro de limpieza; conservar acceso
operativo a la auditoría. No hay endpoint de edición/borrado de auditoría.

## Validación

Pruebas sin BD: `ErpCatalogDeadlineTest`, `ErpCatalogClientTest`,
`ErpWebhookRecoveryTest`, `ErpWebhookRecoverySecurityTest`, junto a regresiones de
cola, worker, sincronización, seguridad, publicaciones y precios. Los peers HTTP son
locales y sintéticos. Se comprueba desconexión real del socket ante cuerpos detenidos,
headers sin cuerpo, goteo, cancelación/interrupción, recuperación posterior y flujo
cliente → sincronizador intacto → worker/backoff sin reconciliación parcial.

`ErpWebhookRecoveryPostgresIT` queda preparada y **no ejecutada**: requiere V12 aplicada
previamente por el propietario, cola TEST vacía, `TEST_DB_PASSWORD` y
`RUN_ERP_WEBHOOK_RECOVERY_IT=true`. Fija 127.0.0.1:55432/miqa_store_test_db, usuario
miqa_store_local; verifica identidad e historial, no inicia Boot/Flyway/DDL y limpia
solo sus UUID sintéticos. Sus seis casos cubren commits/SQL real, replays, concurrencia
con requestId igual/distinto, rollback tras auditoría y recuperaciones sucesivas.
No ejecutar esos comandos sin autorización para el entorno TEST.

Pendiente antes de activar: aplicar/validar V12 y ejecutar esa IT en TEST, además del
monitoreo de antigüedad RETRY/FAILED y dimensionamiento del pool ya documentados en
`ERP_CATALOG_WEBHOOK_V1.md`. Los siete tests PostgreSQL previos del receptor no validan
esta nueva migración ni la recuperación administrativa.
