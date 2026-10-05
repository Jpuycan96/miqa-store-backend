# Cat?logo estructural ERP y fichas editoriales MIQA ? 4 de octubre de 2026

Esta secci?n reemplaza las reglas hist?ricas de publicaci?n descritas m?s abajo.
ERP gobierna categor?as, relaci?n categor?a/servicio, configuraci?n y pricing; MIQA
conserva presentaci?n y aprobaci?n editorial. No hay cambios al contrato ERP ni al frontend.

- El contrato v1 incluye `categoria.erpCategoryId` y `nombreReferencia`. Se proyecta
  en `categories.erp_category_id` (?nico), con ID local UUID y slug estable. No se
  asocia por nombre. Se rechazan categor?as ausentes/inconsistentes en el mismo lote.
  El endpoint solo enumera servicios disponibles: no se inventan categor?as vac?as.
- V10 agrega `products.catalog_mode` (LEGACY/ERP), `bindings.canonical` y unicidad
  parcial del servicio can?nico, incluso desactivado. Los campos t?cnicos legacy
  son nulos para ERP; permanecen obligatorios donde corresponde para LEGACY.
  V1?V9, filas legacy y snapshots no se modifican. V10 todav?a NO aplicada.
- Toda sync v?lida reconcilia fichas, incluso sin cambio de catalogRevision. Una
  principal existente conserva identidad, slug y contenido; solo cambia su categor?a
  si ERP cambia la relaci?n. Sin principal se crea otra ficha con UUID, t?tulo inicial
  ERP, textos vac?os y published=false. Los bindings hist?ricos no se promueven ni
  se reutilizan: se conservan no can?nicos, aunque exista uno solo. Esto implica una
  nueva ficha en borrador para servicios antes vinculados manualmente.
- Slug inicial normalizado, m?ximo 160, con sufijos num?ricos ante colisiones con
  productos, categor?as o aliases de categor?a. Sync nunca renombra URLs existentes.
  Escrituras de slugs admin/sync comparten advisory lock; sync/bind tambi?n se serializan.
  El ?ndice parcial es la garant?a final de unicidad principal. La identidad can?nica
  no puede reasignarse por el PUT de binding; active s? puede cambiarse.
- P?blico exige modo ERP, publicaci?n editorial, categor?a ERP activa, binding
  can?nico activo, proyecci?n AVAILABLE, identidad de categor?a coincidente y contrato
  elegible/disponible/soportado. Listado, detalle, pricing y solicitudes v2 comparten
  resoluci?n. Categor?as y aliases p?blicos requieren alguna ficha visible. Ausencias
  ERP retiran fichas/navegaci?n sin DELETE; reaparici?n reutiliza identidad y respeta
  despublicaci?n/binding desactivado. Errores de red no equivalen a lista vac?a.
- Admin expone catalogMode, canonical y erpCategoryId. Permite t?tulo comercial,
  textos, im?genes, SEO, destacado, orden, slug y publicaci?n del producto ERP.
  Rechaza editar categor?a/tipo/unidad/packs/cantidades/materiales/extras t?cnicos.
  PUT editorial ERP debe reenviar categoryId actual y campos t?cnicos nulos.
  Estructura de categor?as ERP es de solo lectura. El t?tulo comercial se inicializa
  una vez; cambios del nombre t?cnico siguen en el payload ERP, sin sobrescribirlo.
- DTO p?blico conserva campos legacy con null y materiales/extras vac?os; configuraci?n
  ERP contin?a en configuration. No se calcula ni persiste una tarifa local. El frontend
  no fue adaptado: formularios antiguos que obliguen campos t?cnicos requerir?n ajuste.
- Legacy permanece administrable e hist?rico, pero no p?blico. Nuevos env?os legacy
  (v1 o items legacy de v2) devuelven 409. Reintentos confirmados se recuperan antes
  de consultar cat?logo/ERP; snapshots y exportaci?n hist?rica permanecen intactos.
- received/changed/missing siguen midiendo la proyecci?n t?cnica, no altas editoriales.
  Sync manual, sin scheduler/TTL: la disponibilidad refleja el ?ltimo ?xito, no una
  garant?a de actualizaci?n instant?nea. Listados siguen sin paginaci?n.

Validaci?n PostgreSQL pendiente: aplicar V10 ?nicamente en TEST de forma controlada
antes de ejecutar `ErpEditorialPersistenceIT` / `ErpCatalogPersistenceIT`. Usan destino
fijo TEST, fixtures sint?ticos y rollback; no ejecutan Flyway ni HTTP ERP. Las pruebas
HTTP actualizadas necesitan TEST con V10. La validaci?n ejecutada se registra en
PROJECT_CONTEXT.md. No se busc? ninguna credencial ni se accedi? a DEV/PROD.

---

# Fase 2B.2: proyección local ERP

Actualización 2B.4 (30/09/2026): la proyección ahora alimenta la configuración pública
segura de Product y solicitudes v2. Ver [QUOTE_REQUEST_V2.md](QUOTE_REQUEST_V2.md).
Los endpoints y seguridad administrativos descritos aquí se conservan.
El propietario confirmó posteriormente la prueba real 2B.2 con ERP DEV y TEST:
ERP 1 IMPRESIÓN UV/M2 y ERP 101 SELLOS/ESCALA; primera sync 2/2/0 y segunda 2/0/0.
Las limitaciones de ejecución de las secciones inferiores son el registro histórico
de la implementación 2B.2, no una invalidación de esa confirmación.

Implementación exclusiva del backend MIQA. `Product` sigue siendo editorial; sus DTO,
categorías y reglas no cambian. Solicitudes Web conserva `schemaVersion=1` y V7 intactos.
No hay precios, tarifas, configurador público, solicitudes v2, scheduler ni envíos al ERP.

## Persistencia

Nueva migración **V8__erp_catalog_projection.sql**, preparada y **no ejecutada**.
Usa `CREATE TABLE/INDEX IF NOT EXISTS` e inserción del singleton con `ON CONFLICT DO NOTHING`.
No modifica migraciones anteriores. Como cualquier migración, un esquema previamente
creado con definiciones incompatibles requiere revisión; IF NOT EXISTS no lo repara.

- `erp_catalog_services`: clave estable `erp_service_id`, revisión, JSONB tipado y
  permitido por contrato v1, estado local y timestamps. Conserva categoría ERP,
  elegibilidad/disponibilidad, estado/motivos, versiones, evaluatedAt, cantidad,
  medidas, materiales y modelos aprobados. No guarda un cuerpo HTTP arbitrario.
- `product_erp_bindings`: relación independiente con FK restrictivas hacia Product
  y la proyección. **Un único vínculo actual por Product**, activo o desactivado,
  protegido mediante PK `product_id`; una reasignación actualiza ese vínculo.
  `erp_service_id` tiene índice normal, **no UNIQUE**. Varios Product pueden compartirlo.
  Conserva created/updated; no implementa un historial de reasignaciones editoriales.
- `erp_catalog_sync_status`: resultado del último intento, fecha de éxito anterior
  y contadores. No contiene URL, claves, cuerpos remotos ni mensajes de excepción.

Persistencia JDBC con transacciones propias, siguiendo el precedente de Fase 1.
No cambia el transaction manager JPA ni añade asociaciones/campos a Product.
Las categorías ERP permanecen dentro del contrato, sin FK o mapeo a categorías MIQA.

## Configuración externa

| Property | Environment equivalente | Uso |
| --- | --- | --- |
| `app.erp.base-url` | `ERP_TIENDA_VIRTUAL_BASE_URL` | URL raíz del backend ERP, sin la ruta del endpoint |
| `app.erp.api-key` | `ERP_TIENDA_VIRTUAL_API_KEY` | Credencial backend-to-backend |

Las properties tienen prioridad sobre esas variables. Sin configuración la app puede
arrancar y el intento manual responde `503 NOT_CONFIGURED`. No se modifica ningún
application*.properties ni secreto existente. Configurar los valores fuera del repo;
no enviar la clave ERP desde el navegador, ni incorporarla en JSON de estos endpoints.

Cliente Java HTTP dedicado: HTTPS remoto; HTTP únicamente loopback para DEV local.
Rechaza userinfo, query y fragment en la URL. No sigue redirecciones. Timeout de conexión
5 s, solicitud 20 s, cuerpo máximo 10 MiB. Única llamada:
`GET /api/integracion/tienda-virtual/v1/servicios`, con `X-ERP-Service-Key`.
No consulta detalles ni bases de datos ERP. Errores sanitizados sin causas/cuerpos/headers.

## Endpoints administrativos

Todos requieren el JWT administrativo existente; no son endpoints públicos.

| Método y ruta | Resultado |
| --- | --- |
| `POST /api/admin/erp-catalog/sync` | Ejecuta sincronización manual y devuelve resultado |
| `GET /api/admin/erp-catalog/sync` | Estado persistido del último intento |
| `GET /api/admin/erp-catalog/services` | Proyecciones locales, incluida disponibilidad efectiva |
| `GET /api/admin/erp-catalog/bindings/{productId}` | Vínculo y diagnóstico; 404 si no existe |
| `PUT /api/admin/erp-catalog/bindings/{productId}` | Crea/reasigna/activa/desactiva el vínculo |

Body de PUT: `{"erpServiceId":"17","active":true}`. El Product y servicio local deben
existir; si no, 404. Se bloquea la fila del Product durante la escritura para serializar
reasignaciones concurrentes; la PK impide vínculos contradictorios incluso por SQL.
Se permite vincular una proyección pendiente, pero el diagnóstico seguirá pendiente.
Estados del vínculo: `AVAILABLE`, `PENDING_REVALIDATION`, `DISABLED`.

POST devuelve 200 SUCCESS; 502 ERP_ERROR/INVALID_CONTRACT; 503 NOT_CONFIGURED;
409 si otra sincronización está en curso. GET de estado inicial devuelve NEVER.
`received` cuenta servicios recibidos; `changed` inserciones/revisiones distintas;
`missing` servicios marcados pendientes por primera vez en ese intento.
Un fallo ERP conserva succeededAt anterior y pone los contadores del intento a cero.

## Sincronización y límites

1. Bloqueo advisory transaccional PostgreSQL compartido entre instancias, adquirido
   antes del HTTP. Otra sincronización falla inmediatamente con 409.
2. Exige HTTP 200 y **array completo v1 sin paginación**, tal como el controller ERP
   inspeccionado. Rechaza JSON incompleto, trailing tokens, campos desconocidos,
   versión/origen no soportados, IDs duplicados y entradas incompletas/no disponibles.
   Valida estructura, no calcula tarifas ni reemplaza reglas comerciales del ERP.
3. Solo tras validar toda la lista inserta/actualiza por ID estable. Una revisión igual
   conserva JSONB, configurationVersion y evaluatedAt del último contenido guardado;
   únicamente refresca lastSyncedAt y restaura AVAILABLE si estaba pendiente.
4. Los IDs conocidos ausentes quedan PENDING_REVALIDATION, sin borrado ni pérdida de
   payload/vínculos. lastSyncedAt conserva la última vez que fueron vistos disponibles.
   Un array vacío válido sí reconcilia todos; un error o cuerpo inválido no reconcilia nada.
5. Proyección y resultado exitoso se confirman en una transacción. Error de escritura
   revierte todo; en ese caso el estado persistido sigue reflejando el intento anterior
   y el endpoint responde con el error interno sanitizado existente.

`Projection.available` y `syncState` son la autoridad local. `lastKnownErp` es la última
evaluación ERP guardada y puede decir disponible=true aunque el estado local esté pendiente.
No se inventan motivos ERP para ausencias ni se distingue desactivación de eliminación:
eso requerirá consultar detalle en una fase futura. No hay caducidad automática;
lastSyncedAt/succeededAt permiten diagnosticar antigüedad. El catálogo público existente
no consulta ERP ni consume aún estas proyecciones. Listados admin sin paginación en esta fase.

## Validación

Pruebas puras/HTTP simulado y cadena real de seguridad sin autoconfiguración de DB/Flyway:

```powershell
mvn -o '-Dtest=ErpCatalogTest,ErpCatalogClientTest,ErpCatalogSecurityTest,QuoteRequestServiceTest,QuoteRequestCanonicalizerTest,QuoteCatalogTest,QuoteRequestHttpTest,AdminCatalogServiceDeletionTest,CategorySlugTest,ConfigurationTest,ProductionConfigurationTest,TestDatabaseIsolationTest,ManualTestConfigurationTest' clean package
```

La clase `ErpCatalogPersistenceIT` requiere V8 **ya aplicada manualmente** en
`127.0.0.1:55432/miqa_store_test_db`, usuario `miqa_store_local`, y TEST_DB_PASSWORD externa.
Está fuera de la selección automática Surefire por su sufijo IT. No inicia Spring,
Flyway o DDL; crea fixtures sintéticos y revierte cada transacción. Comprueba unicidad
real en BD, varios Product por servicio, compatibilidad editorial, conservación del
payload/vínculos y fecha del último éxito. **Preparada, no ejecutada en esta fase**:

```powershell
# Solo después de preparación manual autorizada de V8 en TEST:
mvn -o '-Dtest=ErpCatalogPersistenceIT' test
```

No ejecutar `mvn test` completo durante esta fase: los tests integrados históricos
inician Flyway. Falta validación real PostgreSQL de V8 y prueba contra ERP DEV;
no se ha abierto conexión a ninguna base ni al ERP durante la implementación.
