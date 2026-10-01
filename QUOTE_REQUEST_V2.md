# Fase 2B.4 — configuración pública y solicitudes v2

Actualizacion 2B.5: los nuevos snapshots ERP incluyen precios revalidados contra ERP.
HTTP se ejecuta fuera de las transacciones locales. El contrato de entrada v2 y sus
hashes se conservan. Esta ampliacion reemplaza las referencias a ausencia de precios
y al flujo de transaccion unica de esta nota historica; ver [PRICING_2B5.md](PRICING_2B5.md).

Implementación local sobre `eefca0d`, con frontend sobre `f358f91`, ambos en
`feature/solicitudes-web`. No commit, push, merge, deploy ni cambios al ERP.

## Inspección y diseño

El catálogo Angular obtiene Product mediante `CatalogApiService` y
`GET /api/public/products` / `GET /api/public/products/{slug}`. Antes de esta fase
solo existían opciones MIQA QUANTITY/PACK/AREA, carrito `miqa.quote.v1` y snapshots
inmutables v1. Los cinco endpoints `/api/admin/erp-catalog/**` son exclusivamente
administrativos y continúan protegidos por JWT. No se abrió ninguno al público.

Se extienden los dos GET existentes con **configuration**, sin un endpoint adicional:

| mode | Significado y comportamiento |
| --- | --- |
| LEGACY | No existe binding; conserva opciones/cantidades MIQA actuales. |
| ERP | Binding activo, proyección AVAILABLE, contrato/configuración soportados. |
| UNAVAILABLE | Existe binding pero está inactivo, pendiente o inválido; conserva la publicación, bloquea configurar/agregar. |

Para ERP, configuration contiene `erpServiceId`, `catalogRevision`,
`configurationVersion` y `configuration` (cantidad, modoMaterial, formaCotizacion,
medidas y materiales/modelos aprobados). Para los otros modos esos campos son null.
No contiene claves, URL ERP, entidades, tarifas, precios, payloads arbitrarios ni
diagnósticos administrativos. PublicErpConfiguration resuelve Product publicado y
categoría MIQA activa, binding y proyección mediante JDBC local. No tiene cliente ERP.
Product sigue editorial; no se modificó su entidad ni se mapearon categorías por nombre.

Frontend interpreta materiales/modelos y campos requeridos. M2 usa ancho/alto,
METRO_LINEAL longitud y ESCALA no tiene medidas en el contrato v1 actual. Campos o
combinaciones futuras desconocidas se rechazan de forma conservadora en backend;
no se inventa una forma de cotizar. Los IDs/versiones son internos, sin mostrarse en UI.

## POST v2 y snapshot histórico

Nuevo **POST /api/public/quote-requests/v2**, con `Idempotency-Key` UUID y
`schemaVersion: 2`. Mismos contacto, respuesta 201/200, referencia MIQA, límite de
50 ítems/64 KiB, rate limit global, CORS explícito y Cache-Control no-store de Fase 1.
Un ejemplo de selección ERP (IDs/revisiones deben corresponder al catálogo actual):

```json
{
  "schemaVersion": 2,
  "contact": { "name": "Cliente", "phone": "999999999" },
  "items": [{
    "productId": "banner",
    "quantity": 2.5,
    "erp": {
      "erpServiceId": "1",
      "catalogRevision": "<revision recibida>",
      "configurationVersion": "<version recibida>",
      "erpMaterialId": "10",
      "measures": { "ancho": 2.5, "alto": 1.2 }
    }
  }]
}
```

`erpModelId` se incluye si el material exige modelo. `measures` contiene exactamente
los campos requeridos distintos de cantidad; ESCALA envía `{}`. No enviar saleType,
packSize, materialId, widthMeters/heightMeters o extras legacy en un ítem ERP.

Una solicitud v2 puede mezclar ítems ERP y legacy en cualquier orden. Los legacy
conservan exactamente los campos de selección v1 y no incluyen `erp`; pasan por el
canonicalizador y validador legacy. Una solicitud exclusivamente legacy sigue usando
el POST original sin schemaVersion. Los intentos ya guardados en sessionStorage
conservan versión, clave y payload; nunca se cambia de endpoint durante un reintento.

Snapshots ERP `schemaVersion=2`: productId/nombre/slug MIQA, erpServiceId/nombre,
categoría ERP de referencia, revisiones, material/modelo seleccionados con sus nombres,
cantidad decimal, medidas, configuración aprobada completa y notas. Los nombres y
configuración se obtienen de la base, nunca del cliente. La configuración queda copiada
en JSONB para conservar significado aunque se renombren o retiren opciones futuras.
Los ítems legacy en una solicitud mixta conservan `QuoteSnapshot` schemaVersion=1.

## Persistencia y compatibilidad

Nueva migración **V9__quote_request_v2_items.sql**, preparada y NO ejecutada. Crea
`quote_request_v2_items`: FK a quote_requests, posición única, productId histórico,
snapshot JSONB v1/v2 y createdAt. No tiene FK al catálogo mutable. Todos los ítems de
un POST v2 se guardan allí, conservando su orden; no se duplican en quote_request_items.

La cabecera, secuencia MIQA e idempotencia siguen en quote_requests. Se reutiliza la
inserción de cabecera existente y una transacción JDBC REPEATABLE_READ para revalidar
y guardar todos los ítems. Cualquier error revierte la solicitud completa. El hash v2
normaliza escala decimal, contacto, notas y orden de claves de medidas. La unicidad
de la clave y recuperación después de carrera usan la restricción existente.

**V1–V8, QuoteSnapshot y el hash/canonicalizador v1 no se modifican.** Las filas
históricas existentes no cambian. Un reintento confirmado se recupera antes de volver
a consultar catálogo, incluso si cambió el binding después del primer envío.

Un Product que ya tiene binding no puede enviar una nueva selección legacy para
evadir la validación ERP: devuelve 409. Esto incluye bindings desactivados; desactivar
un vínculo no se interpreta como volver a reglas locales. Sin binding, comportamiento
legacy íntegro, incluyendo cantidades manuales que no sean múltiplos del step.

Una futura bandeja/exportación de solicitudes debe consultar ambas tablas de ítems
según la solicitud; todavía no existe esa bandeja en esta fase.

## Validaciones y límites explícitos

- Product publicado y categoría MIQA activa; binding activo y servicio AVAILABLE.
- Contrato v1 válido, modos/cardinalidades soportados y coincidencia entre ID de
  proyección y payload; datos corruptos no ofrecen un configurador válido.
- Coincidencia exacta de erpServiceId, catalogRevision y configurationVersion recibidos
  con los actuales; una selección obsoleta responde 409 y requiere reconfigurar.
- Material/modelo pertenecientes al contrato; modelo obligatorio en FIJO/SELECCION,
  ausente en SIN_MODELO. No se aceptan opciones arbitrarias.
- Cantidad BigDecimal dentro de mínimo/máximo, precisión y permiteDecimales;
  multiploObligatorio exacto. incrementoSugerido no es una restricción de divisibilidad.
- Campos de medidas exactos, sin faltantes ni adicionales. ERP v1 solo informa campos
  y unidad, no límites dimensionales. MIQA mantiene su límite de transporte explícito
  de 0.01–1000 metros y seis decimales. Cantidad máxima de transporte 1 000 000 000;
  se respeta además el máximo ERP menor. No son nuevas reglas de tarifas/precios.

El carrito conserva decimales ERP, separa identidades por opciones/revisión, actualiza
cantidades con sus reglas y revalida las selecciones guardadas contra el catálogo al
restaurar. Si dejaron de ser válidas, no las convierte a legacy y muestra un aviso
para volver al producto. El POST vuelve a validar siempre, aunque se manipule storage.
WhatsApp se abre solo después de persistir y muestra cantidades/material/modelo/medidas
del intento enviado; no muestra IDs/versiones ni envía solicitudes al ERP.

## Validación comprobada y pendientes

- Backend: **106 tests, 0 fallos/errores/omitidos**, Java 21 y Maven 3.9.9 offline.
  Nuevas pruebas de proyección pública, selección ERP y servicio v2; regresiones de
  contrato/sync/admin, Fase 1, configuración y aislamiento TEST.
- Frontend: **155/155 tests, 31 archivos**. La primera ejecución simultánea con Maven
  tuvo dos fallos de tiempos de debounce; la repetición completa pasó sin cambios en
  esas pruebas. Casos nuevos: tres formas, selección/mutaciones, decimales, carrito,
  envío mixto, reintento inmutable y WhatsApp posterior al guardado.
- Build frontend de producción offline con `.tmp/quote-offline-build.cjs`: API
  interceptada con fixtures, 13 URLs sitemap, 14 rutas prerenderizadas, archivos SEO
  originales restaurados. Solo warning preexistente catalog.scss 4.11 kB/4 kB.
- Edge local y API simulada: 390/1440 px × M2/ESCALA/METRO_LINEAL/UNAVAILABLE,
  **8 auditorías axe sin infracciones**, sin overflow ni excepciones JS. No llamadas
  productivas. Evidencia local ignorada `.tmp/erp-public-browser-results.json`.
- El repackage del JAR habitual falló por imposibilidad de renombrar el archivo
  existente. No se detuvieron procesos ajenos. Package exitoso en copia aislada:
  `.tmp/phase2b4-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar`, con tests ya
  aprobados y compilación de todas las fuentes/tests de esa copia.
- No se abrió conexión a DB ni ERP ni se ejecutó Flyway. **Pendiente aplicar V9
  manualmente en TEST y validar POST real, persistencia JSONB y reintentos concurrentes
  contra PostgreSQL.** No iniciar este JAR con Flyway habilitado sin esa decisión.
- El catálogo realiza una consulta local de configuración por Product; optimizar
  mediante lectura por lote al crecer el catálogo. No hay caducidad automática de
  proyección, precios, pricingRevision, scheduler, cotización/OT ERP ni deploy.
