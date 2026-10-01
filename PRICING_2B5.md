# Fase 2B.5: precios ERP desde MIQA backend

## Arquitectura y contratos

`POST /api/public/pricing/evaluate` -> `PricingService` -> `PublicErpConfiguration`
-> validador compartido `ErpQuoteSelection` -> `ErpPricing` -> ruta fija ERP
`POST /api/integracion/tienda-virtual/v1/precios/evaluar`.

Entrada publica (nombres ingleses coherentes con quote v2):

```json
{"productId":"<id MIQA>","quantity":1,"erpMaterialId":"10","erpModelId":null,"measures":{"ancho":2,"alto":1.5}}
```

Solo se admiten esas cinco propiedades. Material/modelo/cantidad/medidas se validan
contra la publicacion actual, no contra opciones arbitrarias del navegador.
Product debe estar publicado y su categoria activa; binding activo, proyeccion
AVAILABLE y contrato soportado. MIQA reconstruye servicio, catalogRevision y
configurationVersion. Sin formulas, tarifas, multiplicacion por 1.18 ni cache de precios.

Respuesta disponible: `status`, `amount` (cadena decimal), `currency`,
`includesIgv`, `scope`, `quoteMode`, `billableBase.quantity/unit`.
No incluye revisiones, URL ERP, diagnosticos ni credenciales. En los otros estados
los campos comerciales son null. Producto no publicado/inexistente: 404 habitual;
JSON mal formado: 400 habitual; seleccion estructural incorrecta: 422.

| ERP / condicion | MIQA | HTTP |
| --- | --- | --- |
| PRECIO_DISPONIBLE | PRICE_AVAILABLE | 200 |
| POR_COTIZAR | QUOTE_REQUIRED | 200 |
| CONFIGURACION_OBSOLETA / ERP 404 | CONFIGURATION_STALE | 409 |
| CONFIGURACION_INVALIDA | CONFIGURATION_INVALID | 422 |
| Timeout, conexion, 5xx, 401/403, redirect, contrato roto | TEMPORARILY_UNAVAILABLE | 503 |

401/403 son fallos tecnicos, nunca POR_COTIZAR. Los motivos internos ERP no se
propagan. Una respuesta disponible debe tener contrato/revisiones coherentes,
PEN, IGV incluido, TOTAL_LINEA, forma/unidad publicadas y decimales validos.
No se transforma ni recalcula el importe ERP. Respuestas incoherentes fallan cerradas.

Se reutiliza `ErpCatalogClient` y las variables existentes
`ERP_TIENDA_VIRTUAL_BASE_URL` / `ERP_TIENDA_VIRTUAL_API_KEY`.
La clave solo se coloca en `X-ERP-Service-Key` backend -> ERP; nunca forma parte del
DTO tecnico de negocio, respuesta publica, snapshot ni mensajes de excepcion.
El cliente rechaza una respuesta que refleje literalmente la clave configurada.
HTTPS remoto / HTTP loopback, sin redirects, conexion 5 s, evaluacion completa 20 s,
cuerpo acotado a 10 MiB. No existe proxy publico generico ni URL elegida por el cliente.
CORS POST solo para origins ya configurados. `Cache-Control: no-store`, cuerpo
maximo 64 KiB y presupuesto propio de 120 previews/minuto/instancia; no consume el
presupuesto de envio de solicitudes. Ambos limites son globales, no por IP.

## Solicitudes v2, atomicidad e idempotencia

`POST /api/public/quote-requests/v2` conserva su contrato de entrada 2B.4, respuesta,
referencias y hash. Sus revisiones de seleccion sirven para detectar un carrito
obsoleto; no se usan como autoridad ni se acepta un precio/revision de precio del
navegador. Los campos del request tecnico provienen del snapshot reconstruido.

1. Normaliza y calcula el mismo hash v2 existente.
2. Lee Idempotency-Key en transaccion corta; si esta resuelta, recupera la misma
   referencia sin leer catalogo ni depender del ERP. Otro contenido: 409.
3. Reconstruye todos los snapshots en una lectura REPEATABLE_READ y la cierra.
4. Evalua cada item ERP fuera de transacciones locales. LEGACY no llama al ERP.
5. Abre escritura REPEATABLE_READ; vuelve a comprobar idempotencia y reconstruye
   snapshots locales para detectar cambios ocurridos durante HTTP. Si cambiaron,
   devuelve 409 sin guardar. No hay llamada ERP dentro de esa transaccion.
6. Guarda cabecera y todos los items juntos; cualquier fallo revierte el conjunto.
   Conserva la restriccion unica existente y recuperacion del ganador tras colision.

Cada snapshot ERP nuevo incorpora `pricing` con los campos comerciales, estado,
`pricingRevision` y `evaluatedAt` historicos. QUOTE_REQUIRED queda sin importe,
con fecha/revision si ERP las proporciona. Stale/invalid/temporal rechazan la solicitud
completa antes de insertar; en solicitudes mixtas tampoco se guarda la parte legacy.
Los snapshots antiguos no se reescriben y pueden carecer de `pricing`.
V1, sincronizacion, bindings, catalogo, WhatsApp y frontend conservan su contrato.

No hay migracion nueva ni tabla de precios: se usa JSONB en V9. V1-V9 intactas.
La relectura detecta cambios confirmados antes de la transaccion final; no es una
transaccion distribuida con ERP ni reserva de tarifa. Las evaluaciones de varios
items son secuenciales y pueden tener distintas fechas/revisiones; cada llamada
tiene su timeout de 20 s. El snapshot registra la evaluacion, no garantiza un precio
futuro. No se envia una orden/cotizacion al ERP.

## Validacion automatizada

Se prueban estados ERP, revisiones, importes sin recargo IGV, selecciones locales,
HTTP real contra servidor simulado loopback, timeout real, 401/403, redirects,
reflejo de clave sintetica, logs capturados, CORS, campos prohibidos, cuerpo acotado,
snapshots disponibles/por cotizar, LEGACY/mixtos, rollback, cambios durante HTTP,
colisiones y reintento resuelto sin ERP. Nunca se usan fixtures DEV/PROD.

Comando unitario/regresion (excluye las cuatro clases que requieren PostgreSQL):

```powershell
$env:JAVA_HOME = (Resolve-Path '.tmp/jdk21/jdk-21.0.12.1+1').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -o '-Dtest=*Test,!AdminApiTest,!CatalogApiTest,!QuoteRequestApiTest,!ProductionAdminBootstrapTest' test
```

Para la suite completa, SOLO con credenciales TEST cargadas y destino TEST verificado:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'test'
.\mvnw.cmd test
.\mvnw.cmd package
# Despues de Flyway V9 en TEST; no ejecuta migraciones por su cuenta:
.\mvnw.cmd '-Dtest=PricingSnapshotPersistenceIT,ErpCatalogPersistenceIT' test
```

`PricingSnapshotPersistenceIT` usa conexion fija a miqa_store_test_db:55432,
fixtures sinteticos y rollback; verifica round-trip JSONB disponible y por cotizar.
La secuencia PostgreSQL puede avanzar aun con rollback. No omite silenciosamente
la prueba si falta TEST_DB_PASSWORD. Los IT no se incluyen por defecto en Surefire.
Consultar PROJECT_CONTEXT.md para resultados y limitaciones comprobadas de esta entrega.

## Validacion manual por el propietario

Estos comandos NO fueron ejecutados contra ERP DEV por el agente.
Requieren ERP DEV en 127.0.0.1:8080, PostgreSQL TEST en 127.0.0.1:55432 con
miqa_store_test_db, y Product publicado TEST vinculado a servicio 1 y sincronizado.
Si falta, sincronizar/vincular desde administracion TEST existente antes de continuar;
no usar datos de produccion. Aplicar V8/V9 en TEST mediante el arranque normal
controlado de MIQA; no editar migraciones. Mantener ERP y MIQA en bases separadas.

1. En PowerShell de MIQA, cargar la clave sin mostrarla ni guardarla en un archivo:

```powershell
cd D:\MIQA-STORE\miqa-store-backend
$env:JAVA_HOME = (Resolve-Path '.tmp/jdk21/jdk-21.0.12.1+1').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ERP_TIENDA_VIRTUAL_BASE_URL = 'http://127.0.0.1:8080'
$secureKey = Read-Host 'Clave tecnica ERP DEV' -AsSecureString
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try { $env:ERP_TIENDA_VIRTUAL_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer); $secureKey.Dispose() }
try {
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-TestApp.ps1
} finally {
    Remove-Item Env:ERP_TIENDA_VIRTUAL_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:ERP_TIENDA_VIRTUAL_BASE_URL -ErrorAction SilentlyContinue
}
```

El helper pide TEST_DB_PASSWORD de forma oculta y usa solo TEST. El proceso queda
atendiendo en 8081; Ctrl+C al finalizar. No introducir la API key en frontend,
Postman publico, JSON de seleccion, Git o application*.properties.

2. En otra terminal, seleccionar la publicacion y consultar el precio:

```powershell
$miqa = 'http://127.0.0.1:8081'
$products = Invoke-RestMethod "$miqa/api/public/products"
$product = @($products | Where-Object {
    $_.configuration.mode -eq 'ERP' -and $_.configuration.erpServiceId -eq '1'
}) | Select-Object -First 1
if ($null -eq $product) { throw 'Falta publicacion TEST vinculada y sincronizada con servicio 1' }
$selection = @{
    productId = $product.id
    quantity = 1
    erpMaterialId = '10'
    erpModelId = $null
    measures = @{ ancho = 2; alto = 1.5 }
}
$priceJson = $selection | ConvertTo-Json -Depth 8 -Compress
Invoke-RestMethod "$miqa/api/public/pricing/evaluate" -Method Post -ContentType 'application/json' -Body $priceJson
```

Esperado para la tarifa de referencia proporcionada: PRICE_AVAILABLE, amount
105.00 PEN, includesIgv true, billableBase 3.0 M2. No esta hardcodeado; si ERP cambio
la tarifa, la respuesta debe reflejar ERP. Ninguna revision ni key en esa respuesta.

3. Crear solicitud v2 (contacto sintetico TEST) y repetir exactamente el envio:

```powershell
$quote = @{
    schemaVersion = 2
    contact = @{ name = 'Prueba precios TEST'; phone = '999999999' }
    items = @(@{
        productId = $product.id
        quantity = 1
        erp = @{
            erpServiceId = $product.configuration.erpServiceId
            catalogRevision = $product.configuration.catalogRevision
            configurationVersion = $product.configuration.configurationVersion
            erpMaterialId = '10'
            erpModelId = $null
            measures = @{ ancho = 2; alto = 1.5 }
        }
    })
}
$quoteJson = $quote | ConvertTo-Json -Depth 12 -Compress
$idempotencyKey = [guid]::NewGuid().ToString()
$headers = @{ 'Idempotency-Key' = $idempotencyKey }
$created = Invoke-WebRequest -UseBasicParsing "$miqa/api/public/quote-requests/v2" -Method Post -Headers $headers -ContentType 'application/json' -Body $quoteJson
$created.StatusCode # 201
$confirmation = $created.Content | ConvertFrom-Json
$confirmation.reference
$replayed = Invoke-WebRequest -UseBasicParsing "$miqa/api/public/quote-requests/v2" -Method Post -Headers $headers -ContentType 'application/json' -Body $quoteJson
$replayed.StatusCode # 200, misma referencia
```

4. Comprobar historico solo en TEST, usando psql y password interactivo (sin PII):

```powershell
# Sustituir MIQA-XXXXXX por la referencia anterior.
psql -h 127.0.0.1 -p 55432 -U miqa_store_local -d miqa_store_test_db -W
```

```sql
SELECT current_database();
SELECT q.reference, i.position, i.snapshot->'pricing' AS pricing
FROM quote_requests q JOIN quote_request_v2_items i ON i.request_id=q.id
WHERE q.reference='MIQA-XXXXXX' ORDER BY i.position;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
```

Esperado: pricingRevision/evaluatedAt persistidos, importe ERP sin recargo,
una cabecera y los items en orden; Flyway V1-V9 correctas. No consultar contactos.

5. Detener temporalmente SOLO el ERP DEV propio. Repetir el POST v2 con `$headers`
y `$quoteJson` exactos: 200 y misma referencia aun sin ERP. Una nueva clave con el
mismo cuerpo debe devolver 503 TEMPORARILY_UNAVAILABLE y no crear solicitud;
el preview tambien debe devolver 503. Volver a iniciar ERP DEV.

6. Material inexistente o medidas incompletas en preview: 422 sin llamada ERP.
Seleccion v2 con catalogRevision obsoleta y clave nueva: 409 sin persistencia.
POR_COTIZAR/stale/invalid remotos y 401/403 estan cubiertos por el simulador;
probarlos manualmente solo con fixtures/configuracion DEV propios. Un item LEGACY
sin binding sigue enviandose sin ERP. Una solicitud mixta se guarda completa solo
si todos sus items ERP se evaluan correctamente.
