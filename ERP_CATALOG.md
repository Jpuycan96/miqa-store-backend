# Fase 2B.2: proyección local ERP

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
