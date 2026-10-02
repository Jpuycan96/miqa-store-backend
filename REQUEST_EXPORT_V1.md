# Fase 2B.6.1 - Exportacion tecnica de solicitudes MIQA

API entrante exclusivamente READ-ONLY. MIQA conserva el registro original. No hay
sincronizacion, ACK, outbox, importador, push, cambios de estado o cotizaciones ERP.
No consulta el catalogo vivo ni hace llamadas al ERP.

## Seguridad y configuracion

Se conserva el patron de header tecnico `X-ERP-Service-Key`. En MIQA el patron
existente era saliente; no habia autenticacion tecnica entrante ni permisos de
exportacion. Se agrega una cadena Spring Security dedicada a `/api/integracion/erp/**`
antes de la cadena administrativa, sin modificar esta ultima ni catalogo/precios.

Configuracion externa, vacia por defecto y sin secretos en properties del repositorio:

| Variable | Propiedad equivalente | Uso |
| --- | --- | --- |
| MIQA_ERP_REQUESTS_API_KEY | app.erp.requests.api-key | Credencial ENTRANTE independiente para el lector ERP |
| MIQA_ERP_REQUESTS_SCOPES | app.erp.requests.scopes | Debe incluir exactamente `solicitudes:read` (separadores espacio/coma) |

Usar una clave aleatoria independiente de la clave SALIENTE
ERP_TIENDA_VIRTUAL_API_KEY. No hay fallback ni intercambio de permisos con esa clave
ni con JWT admin. Se requiere aprovisionar ambas variables para habilitar lectura.
Clave no vacia: 32-512 caracteres ASCII imprimibles sin espacios; configuracion
invalida detiene el arranque con un mensaje sin valores. Vacia: endpoint cerrado.
Se conserva solo SHA-256 en el filtro y se compara con MessageDigest.isEqual.
Ausente/incorrecta/duplicada: 401. Clave valida sin alcance: 403. No se acepta un
scope aportado por el navegador/consumidor, un query param como credencial ni Bearer
admin como sustituto. Solo GET autorizado; POST/PUT/PATCH/DELETE/HEAD/OPTIONS 403.
No se habilita CORS para esta API ni sesiones/cookies. HTTPS en infraestructura
real; no incluir esta clave en frontend, URLs, logs o snapshots.

Todos los resultados de la cadena tecnica, incluidos errores, llevan
`Cache-Control: no-store`. Ningun codigo nuevo registra contacto, cuerpo o clave;
los DTOs que contienen contacto/notas y filas internas tienen toString redactado.
La credencial no aparece en el Authentication ni en sus detalles. El listado no
lleva datos personales; el detalle lleva solo contacto necesario y notas historicas.
No se exportan idempotency_key, request_hash, reference_number ni payload JSON crudo.

El helper local Start-TestApp.ps1 bloquea variables MIQA_* por su politica general;
no se cambia ese helper en esta fase. Para una futura prueba integrada, revisar su
allowlist explicita o aprovisionar las propiedades en el lanzamiento TEST controlado.
No relajar la guarda de base de datos ni reutilizar un perfil DEV por ese motivo.

## Endpoints

`GET /api/integracion/erp/v1/solicitudes`

Parametros de inicio:
- `createdFrom`: ISO-8601 UTC inclusivo. Default `1970-01-01T00:00:00Z`.
- `createdBefore`: ISO-8601 UTC exclusivo. Default hora de PostgreSQL al iniciar
  el recorrido; queda fija en todas sus paginas.
- `limit`: 1-100, default 50. Es cantidad de cabeceras, no de items.

Fechas con precision maxima de microsegundos, from < before, limite superior ano
9999. No hay filtros de estado comercial que puedan ocultar solicitudes.

Respuesta:

```json
{
  "contractVersion": 1,
  "sourceSystem": "MIQA_STORE",
  "window": {"createdFrom":"2026-10-01T00:00:00Z","createdBefore":"2026-10-02T00:00:00Z"},
  "requests": [{
    "id":"<id interno MIQA>","reference":"MIQA-000017",
    "origin":"TIENDA_VIRTUAL","status":"RECIBIDA",
    "createdAt":"2026-10-01T10:00:00Z","updatedAt":"2026-10-01T10:00:00Z"
  }],
  "nextCursor":"<token o null>",
  "hasMore":true
}
```

Siguiente pagina: `GET /api/integracion/erp/v1/solicitudes?cursor=<nextCursor>`.
No combinar cursor con createdFrom/createdBefore/limit. Cuando no hay mas filas:
nextCursor=null y hasMore=false, incluida una ventana vacia.

`GET /api/integracion/erp/v1/solicitudes/{id}`

Busca por ID interno estable, NO por referencia o nombre. Devuelve 404 si no existe.
Respuesta detalle: contractVersion/sourceSystem, id/reference/origin/status,
createdAt/updatedAt, contact {name,phone,email}, notes e items ordenados.

Cada item contiene:
- `id`: `v1:<id fila>` o `v2:<id fila>` segun la tabla historica. Es estable y evita
  colisiones entre las dos tablas; no indica por si solo el tipo comercial.
- `position`, `snapshotVersion` y `type` (`LEGACY` o `ERP`).
- `legacy`: QuoteSnapshot v1 conocido, o null. Conserva Product MIQA id/nombre/slug,
  categoria MIQA, tipo/cantidad/unidad/pack, widthMeters/heightMeters/areaSquareMeters,
  material y extras MIQA, reglas y notas. NO agrega equivalencias ERP.
- `erp`: ErpSnapshot v2 conocido, o null. Conserva producto MIQA, servicio ERP
  id/nombre, categoria ERP historica, material/modelo ERP id/nombre, cantidad,
  measures, configuration (incluidas unidades), catalogRevision,
  configurationVersion, notas y `pricing` historico completo.

Pricing conserva status/amount/currency/includesIgv/scope/quoteMode/billableBase,
pricingRevision y evaluatedAt exactamente como fueron persistidos. No reevalua
precios ni vuelve a calcular IGV. Pricing ausente historicamente -> null.
LEGACY tambien puede estar almacenado en quote_request_v2_items; se interpreta
schemaVersion del snapshot, sin inferir ERP por tabla, nombre o configuracion actual.

Los registros se deserializan a DTOs conocidos: propiedades desconocidas de JSON
persistido no se propagan. Snapshot corrupto, version no soportada, Product ID
inconsistente o cabecera sin items -> 409 INVALID_HISTORICAL_SNAPSHOT sin payload;
no se salta silenciosamente ese item. Error de cursor/rango -> 400
INVALID_EXPORT_QUERY. Errores inesperados siguen el handler sanitizado existente.

## Cursor, recuperacion e idempotencia del futuro consumidor

Orden SQL exacto: `created_at ASC, id COLLATE "C" ASC`.
Keyset estricto despues de `(lastCreatedAt,lastId)`, dentro de
`created_at >= createdFrom AND created_at < createdBefore`. Consulta `limit+1` para
detectar si hay otra pagina; no OFFSET ni comparaciones contra MIQA-xxxxxx.

Cursor versionado Base64URL sin padding sobre ASCII:
`1|limit|createdFrom|createdBefore|lastCreatedAt|lastId`.
Longitud maxima 512, validacion estricta/canonica; fechas y ID se usan como parametros
SQL. No incluye PII ni secretos. Es opaco para el consumidor, no firmado/cifrado;
no otorga permisos ni restringe el conjunto autorizado: cambiar un rango no concede
acceso adicional, ya que toda peticion exige la misma identidad y alcance de lectura.
No caduca ni depende de una clave criptografica; rotar la clave no invalida el cursor.

Cada peticion usa transaccion JDBC corta readOnly/REPEATABLE_READ. El detalle obtiene
cabecera e items bajo la misma vista. No mantiene una transaccion entre paginas.
Por ello, el orden es determinista para las filas visibles, pero NO se promete que
una pagina sea una copia inmutable si entre peticiones aparecen commits tardios.
Repetir puede entregar filas ya vistas; sus IDs y los de sus items no cambian.

**El cursor NO es un watermark de commits.** CreatedAt se asigna antes del commit:
una transaccion A puede tener fecha/ID anteriores a B pero confirmar despues de que
ERP haya pasado por B. Un cursor createdAt+id, por si solo, tampoco resuelve eso.

Protocolo que debe aplicar ERP en 2B.6.2 (no implementado aqui):
1. Guardar window y cursor solo despues de procesar exitosamente la pagina.
2. Hacer upsert por `(sourceSystem,id)`; para items por `(sourceSystem,requestId,itemId)`.
   Nunca insertar de nuevo por referencia, nombre ni por el simple hecho de repetir.
3. Reanudar con el mismo cursor tras fallos, incluso despues de reiniciar MIQA.
4. Recorrer ventanas incrementales con solapamiento como optimizacion y repetir
   ventanas completas (sin cursor inicial) para reconciliacion.
5. Ejecutar reconciliaciones completas periodicas desde el inicio del historial.
   Un solapamiento finito no garantiza encontrar una transaccion arbitrariamente
   tardia. No descartar para siempre ventanas antiguas ni tratar hasMore=false como
   prueba de que ya confirmaron todas sus transacciones.
6. Ante un snapshot invalido, registrar/reintentar por ID o resolver el caso; no
   marcarlo como importado correctamente. Para futuras modificaciones de cabecera,
   updatedAt permite comparar versiones, pero esta API no es un change feed.

Ejemplo: A(10:00,a) sigue abierta, B(10:00,b) confirma. Primera pagina entrega B.
A confirma. Continuar despues de B no devuelve A; repetir la ventana desde el
principio si devuelve A. La idempotencia por ID absorbe que B vuelva a aparecer.
La reconciliacion completa recupera tambien A cuando su createdAt precede incluso
el limite inferior de una ventana incremental. Sin ACK/outbox, no se garantiza
entrega exactamente una vez ni completitud instantanea; se permite recuperacion
convergente de todos los registros persistidos que permanezcan en MIQA.

## Validacion

Pruebas focalizadas primero, Java 21 / Maven Wrapper:

```powershell
.\mvnw.cmd -o '-Dtest=RequestExport*Test' test
```

Suite relevante posterior, una sola pasada con package, sin PostgreSQL:

```powershell
.\mvnw.cmd -o '-Dtest=*Test,!AdminApiTest,!CatalogApiTest,!QuoteRequestApiTest,!ProductionAdminBootstrapTest' package
```

En esta maquina se usa salida aislada `.tmp/phase2b61-build` con las mismas fuentes
y recursos, para evitar interferencias ya documentadas del editor sobre target.
POM versionado intacto. Resultados comprobados en PROJECT_CONTEXT.md.

Prueba PostgreSQL preparada, SOLO tras V9 y con TEST_DB_PASSWORD cargada externamente:

```powershell
.\mvnw.cmd '-Dtest=RequestExportPersistenceIT' test
```

Ese IT no ejecuta Flyway/DDL, no arranca Boot ni consulta ERP; destino fijo
127.0.0.1:55432/miqa_store_test_db. Usa dos conexiones para confirmar transacciones
fuera de orden y verificar reconciliacion. Usa solo fixtures sinteticos propios,
limpia exclusivamente sus IDs al terminar y puede avanzar la secuencia aun tras
limpieza. Requiere V1-V9 ya aplicadas. No se incluyen los IT por defecto en Surefire.
No hay cambios de migraciones/esquema ni escritura en los endpoints implementados.
