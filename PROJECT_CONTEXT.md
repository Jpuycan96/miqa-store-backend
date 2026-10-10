# MIQA Store Backend — contexto de proyecto

## SEO dinamico, etapa 1 exclusivamente local - 10 de octubre de 2026

- GET publico `/api/public/seo/sitemap.xml`, XML UTF-8 y `Cache-Control: no-store`. Dominio fijo `https://store.solucionesmicaela.com`, Inicio, `/productos` y rutas `/productos/{slug}` de categorias/productos. Reutiliza `CatalogService.categories()` y `products(null, null, null)` en una transaccion read-only REPEATABLE_READ, sin duplicar reglas ni consultar ERP. Categorias activas con identidad ERP y productos elegibles; publicaciones, vinculos canonicos activos y contratos AVAILABLE/soportados siguen gobernados por las consultas publicas actuales. Categorias vacias/inactivas, borradores, despublicados y no elegibles quedan excluidos.
- URLs deduplicadas entre productos/categorias, segmentos codificados y XML generado con StAX del JDK; sin `lastmod` inventado, metadatos privados ni datos tecnicos ERP. Fallos de consulta/render generan HTTP 500 sin cuerpo parcial, aun con Accept XML; logs solo con tipo de excepcion, sin mensajes/causas SQL. Catalogo publico legitimamente vacio conserva las dos URLs estaticas.
- DTO publico Product suma `seoTitle` y `seoDescription` existentes, en listado/detalle, preservando null/vacio y valores editoriales. Entidades, consultas, reglas comerciales, publicaciones, precios, sincronizacion y migraciones sin cambios. Sin dependencias nuevas.
- Validacion Java 21/Maven offline: 23 pruebas aprobadas, 0 fallos, errores u omisiones; CatalogSeoTest 4, SitemapHttpTest 7, PublicErpConfigurationTest 5, ErpEditorialCatalogTest 6 y ErpConfigurationVersionRegressionTest 1. Package BUILD SUCCESS. Reutilizado POM temporal existente `.tmp/webhook-reliability-build/pom.xml`, fuentes/recursos del proyecto y salida ignorada `.tmp/webhook-reliability-build/target`; pom.xml/dependencias intactos. Lectura ampliada de cache Maven necesaria por permiso denegado del sandbox sobre JAR existente. Pruebas MVC con seguridad real y repositorios/proyeccion simulados, sin Boot integrado/BD/Flyway/ERP ni servidores reales.
- Alcance pendiente: no se expuso `/sitemap.xml` del dominio ni se conecto Cloudflare; HTML prerenderizado/SSR, robots, redirecciones y respuestas de paginas siguen fuera de esta etapa. Sitemap sin paginacion: antes de superar limites del protocolo, dividirlo; las consultas actuales materializan DTOs completos. Validacion futura con BD/entorno integrado requiere autorizacion separada.
- Frontend intacto; hashes de `public/sitemap.xml` (cambio preexistente), `scripts/generate-sitemap.mjs` y `public/_redirects` conservados. Sin ERP/GoPrint, migraciones, accesos a produccion, commit/push/deploy ni cambio de rama. Backend main inicialmente limpio; todos los cambios quedan locales y sin staging.

## Validacion PostgreSQL de recuperacion V12 - 9 de octubre de 2026

- Operacion autorizada exclusivamente en el cluster TEST existente: PostgreSQL 17.6, 127.0.0.1:55432/miqa_store_test_db, miqa_store_local, directorio fisico .local/postgres-test/data. Verificados marca TEST, rutas sin links, configuracion efectiva confinada al directorio TEST, PID/listener, propietario, usuario/sesion, esquemas e historial. No hubo PID obsoleto ni reinicializacion. Procesos PostgreSQL externos conservaron los siete PID previamente verificados por el propietario; ningun acceso a 5432/DEV/produccion.
- Contraseña efimera exclusiva TEST generada en memoria; SCRAM offline bajo guardas ya autorizadas, sin alterar pg_hba.conf ni otras configuraciones/clusters. ExecutionPolicy Bypass solo del proceso PowerShell. No se conservaron credenciales utilizables ni se habilitaron webhooks.
- Flyway valido doce migraciones y aplico exclusivamente V12__erp_webhook_admin_requeues.sql, desde esquema V11. SQL V1-V12 intacto. Las 300 huellas de filas completas, historial V1-V11, columnas, indices, restricciones y secuencias anteriores coincidieron despues de migrar y despues de las pruebas.
- ErpWebhookRecoveryPostgresIT: seis pruebas PostgreSQL aprobadas, 0 fallos, 0 errores, 0 omisiones, 6.091 s. Cobertura: reencolado/historial y procesamiento, replays, concurrencia con requestId igual/distinto, rollback real tras insertar auditoria, reutilizacion entre eventos y fallos permanentes sucesivos. Se corrigio exclusivamente la consulta de IP de setup: host(inet_server_addr()) evita el sufijo /32 de inet::text, conservando la comprobacion exacta de loopback. La primera ejecucion fallo en esa asercion antes de crear fixtures; se preservo el codigo comercial.
- Verificacion final: cola y auditoria vacias; ocho columnas obligatorias, seis restricciones de auditoria validadas, FK restrict, cinco indices de eventos y dos de auditoria validos/listos, indice FAILED parcial, sin advisory locks ajenos. Ocho probes sinteticos de PK/FK/texto/contador/resultado/NOT NULL/delete restrict rechazados con SQLSTATE esperado y rollback completo; datos e historial preservados.
- Evidencia ignorada en .tmp/miqa-recovery-pg-run/output/surefire-reports/TEST-com.miqa.store.webhook.ErpWebhookRecoveryPostgresIT.xml y preserved-data.properties (solo hashes, sin filas/secretos). Arnes temporal sin Boot con resources vacios y Maven offline; pom.xml/dependencias y codigo comercial intactos. La validacion inicial del arnes debio permitir V12 pendiente; una ruta temporal de reporte se corrigio al comprobar el XML real y completar la verificacion final por separado.
- Cierre: PostgreSQL TEST detenido, 55432 libre, postmaster.pid ausente. git diff --check correcto; main conservada, sin commit/push/deploy/staging. Cambios anteriores preservados. V12 validada solo en TEST; DEV/produccion, activacion y monitorizacion/retencion siguen pendientes. Esta seccion supersede los pendientes de V12/IT en la entrega anterior, sin reinterpretarlos como validacion productiva.

## Confiabilidad HTTP y recuperacion de FAILED - 9 de octubre de 2026

- Trabajo exclusivo backend; preservados cambios anteriores del receptor, properties y V11. Sin conexiones BD/ERP reales, Flyway, activacion de flags, frontend/ERP, commit/push/deploy ni cambio de main.
- ErpCatalogClient.fetchAvailable usa deadline total monotono de 20 s con sendAsync/get hasta cuerpo completo y cancelacion en finally; headers sin cuerpo, cuerpo parcial o goteo no retienen indefinidamente al worker. Conserva timeout de conexion, limite de memoria, clave/ruta y evaluatePrice. Timeout/interrupcion -> ERP_ERROR, interrupcion preservada y backoff existente; no reconcile de respuesta parcial.
- ErpWebhookQueue suma listado FAILED acotado 1-100 y reencolado individual FAILED -> PENDING. Endpoint administrativo con JWT existente de usuario activo, actor obtenido del sub, motivo y requestId UUID obligatorio. Bloqueo de fila, auditoria y actualizacion en transaccion independiente; commit antes de 202. Replays de requestId no vuelven a reencolar aunque el worker haya procesado o vuelto a fallar; conflictos 409 y rollback completo. Se preservan identidad/hash, contador acumulado y ultimo diagnostico; el worker usa agrupacion/backoff actuales.
- V12__erp_webhook_admin_requeues.sql nueva, NO aplicada: auditoria durable de actor/motivo/operacion/intentos/resultado previos y fechas; FK restrict e indices. V1-V11 y ErpCatalogService.synchronize() intactos por checksum. Datos/publicaciones/editorial/precios sin cambios. Procedimiento y limitaciones en ERP_WEBHOOK_RECOVERY.md; README enlaza la guia.
- Validacion sin BD: 85 pruebas focalizadas/regresion aprobadas, 0 fallos/errores/omisiones, y package Java 21/Maven offline BUILD SUCCESS. Incluye siete casos de deadline/cancelacion/pipeline, seis de recuperacion y seguridad MVC/JWT real, ademas de cola, worker, catalogo/editorial, exportacion y pricing. Tras endurecer el conflicto concurrente de requestId y la validacion del motivo, las 22 pruebas afectadas y package volvieron a pasar. Salida aislada .tmp/webhook-reliability-build; pom.xml/dependencias intactos. Maven requiere acceso de lectura ampliado a cache por AccessDenied de javac al resolver un JAR en el sandbox.
- ErpWebhookRecoveryPostgresIT: seis pruebas preparadas/compiladas, NO ejecutadas, opt-in RUN_ERP_WEBHOOK_RECOVERY_IT=true y TEST_DB_PASSWORD, destino TEST fijo y V12/cola vacia requeridas. Cobertura SQL/concurrencia/rollback/auditoria pendiente de autorizacion TEST; los siete tests PostgreSQL anteriores del receptor no acreditan esta nueva funcionalidad. Antes de activar: aplicar/validar V12 e IT y vigilar RETRY/FAILED; retencion conjunta de recibos/auditoria pendiente. git diff --check correcto.

## Etapa 1: receptor seguro de eventos ERP - 9 de octubre de 2026

- Inicio main limpio; trabajo exclusivo MIQA backend. WORKFLOW.md no existe. Sin modificaciones a frontend/ERP, produccion, secretos reales, contratos publicos, precios ni ERP_PRICING_DIAGNOSTIC.md. Sin commit/staging/push/deploy/cambio de rama.
- POST `/api/integracion/erp/v1/catalogo/eventos`, cadena de seguridad tecnica independiente y prioritaria respecto a exportacion. HMAC-SHA256 del timestamp + LF + cuerpo original, comparacion constante, ventana temporal, maximo 4096 bytes y contrato estricto ERP_CATALOG_CHANGED v1. JWT administrativo y X-ERP-Service-Key no autentican esta ruta. Recepcion/procesamiento false por defecto; secreto exclusivo externo.
- V11 nueva, NO ejecutada: cola durable de metadatos/hash, PK eventId, estados e indices. Commit independiente antes de 202; duplicado identico 200 sin reencolar y mismo ID/cuerpo distinto 409. Nunca HTTP ERP dentro del receptor.
- Trabajador opt-in consulta solo cola local; agrupa hasta 200 eventos con ventana 3 s y usa ErpCatalogService.synchronize() SIN modificarlo. Advisory lock de sesion 724193820128 con conexion dedicada, liberacion explicita/abort ante incertidumbre, token de lote para proteger reconocimientos y recuperacion de PROCESSING tras reinicio. Mantiene bloqueo transaccional de sync 724193820126 y reglas editoriales/comerciales existentes.
- RETRY para ERP_ERROR/SYNC_BUSY/WORKER_ERROR, exponencial 30-900 s por defecto sin descartar caidas prolongadas; eventos nuevos no evaden cooldown. NOT_CONFIGURED/INVALID_CONTRACT quedan FAILED para diagnostico; reencolado operativo pendiente. Garantia al menos una vez: caida entre sync y reconocimiento puede repetir reconciliacion idempotente. No locks durante espera/backoff. Pool minimo 2 y conexiones de sesion estables; fuera de admin-bootstrap.
- Validacion: 62 pruebas focalizadas/regresion aprobadas y package Java 21/Maven offline BUILD SUCCESS; despues 9 pruebas HTTP y package aprobados al sumar rechazo de JWT administrativo realmente valido. Reportes finales: 63 pruebas distintas, 0 fallos/errores/omisiones (27 nuevas, 36 existentes). POM temporal ignorado equivalente al original, salida `.tmp/webhook-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar`; Maven instalado/cached explicito porque Wrapper no inicia. pom.xml y dependencias intactos.
- ErpWebhookPersistenceIT opt-in compilada, NO ejecutada: requiere V11 previamente aplicada por propietario en miqa_store_test_db aislada/cola vacia, TEST_DB_PASSWORD y RUN_ERP_WEBHOOK_PERSISTENCE_IT=true. Cubre SQL, commits/conexiones reales, dedup concurrente, recovery/backoff/tokens y advisory locks; no Boot/Flyway/DDL. Ninguna conexion BD/ERP ni migracion ejecutada en esta tarea. Validacion real PostgreSQL y despliegue siguen pendientes.
- Contrato exacto, variables/defaults, errores, limites de pool/particiones, retencion y decisiones del futuro emisor en [ERP_CATALOG_WEBHOOK_V1.md](ERP_CATALOG_WEBHOOK_V1.md). Este cambio no incluye emisor ERP ni retiro del boton manual. Los flags no deshabilitan Flyway en un futuro arranque normal.

## Correccion de configurationVersion en sincronizacion - 5 de octubre de 2026

- Evidencia real aportada por el propietario, no llamada nueva a produccion: ERP devuelve PRICE_AVAILABLE/HTTP 200 con catalogRevision igual pero configurationVersion 1 mientras snapshot MIQA envia 0. El JAR diagnostico ya fue retirado de produccion por el propietario.
- Causa MIQA: sync comparaba solo catalogRevision y repository.seen preservaba JSONB/configurationVersion anterior. ERP exporta c.version (campo JPA @Version); su hash canonical excluye el contador, por lo que version cambia sin cambiar hash. Decode/persistencia/publicacion/snapshot no aplican un default 0. ErpPricing rechaza correctamente el mismatch.
- Fix generico: comparar tambien configurationVersion almacenada en payload; version distinta/ausente hace upsert completo. Ambas iguales mantienen freshness write y payload/evaluatedAt. Resync posterior al fix repara filas anteriores sin migracion ni SQL manual; solo usa la version exportada por ERP. Preservadas validaciones reales de obsolescencia, identidad editorial y snapshots historicos.
- Regresion con JDBC/ERP simulados, pipeline real sync -> payload -> publico -> pricing: request 0/ERP 1 produce STALE antes; resync mismo hash guarda 1 y request 1 obtiene precio; siguiente sync es idempotente; mismatch posterior 1/2 sigue rechazado. 29 tests focalizados aprobados, package Java 21/Maven offline BUILD SUCCESS; sin BD/Flyway/ERP reales.
- Retirada instrumentacion temporal ErpPricing y sus tests/documento; desaparece el flag. Conservado el PENDIENTE de retiro Legacy y cambios previos de contexto. Frontend y ERP sin modificaciones por esta tarea. Detalles/comandos locales en ERP_CATALOG.md. Sin commit/push/deploy/cambio de main ni acceso produccion.


## PENDIENTE — Retiro de Legacy MIQA

**NO ejecutar todavía.** Primero debe estabilizarse y validarse en producción el nuevo catálogo gobernado por ERP. Después, retirar de forma controlada el sistema Legacy de catálogo MIQA que ya no tenga función.

La investigación previa deberá inventariar:

- Código backend obsoleto y código frontend relacionado con Legacy.
- Endpoints, DTOs, servicios y repositorios exclusivos del catálogo anterior.
- Tablas, columnas y datos de BD sin uso; configuración técnica antigua en MIQA para categorías, materiales, modelos, medidas, precios, etc.
- Compatibilidad temporal introducida durante la migración ERP, seeds y datos Legacy, tests del flujo retirado y documentación obsoleta.

No borrar ni editar migraciones Flyway ya aplicadas. No eliminar datos históricos sin determinar antes si deben conservarse; preservar imágenes, información editorial e historial que todavía tengan valor.

Antes de eliminar datos en producción, seguir: **inventario -> análisis de referencias -> backup -> migración formal de limpieza -> pruebas -> producción**. No hacer DROP ni DELETE manual improvisado en producción.

Objetivo final: ERP como única autoridad de estructura/configuración técnica del catálogo; MIQA conserva únicamente la responsabilidad editorial/presentacional necesaria.

## Cat?logo estructural ERP y ficha editorial autom?tica ? 4 de octubre de 2026

- Inicio: main limpio. ERP solo le?do para confirmar categoria.erpCategoryId; frontend intacto. Sin commit/staging/push/merge/deploy/cambio de rama ni acceso DEV/PROD.
- V10 nueva, NO aplicada: identidad ERP de categor?a ?nica, Product LEGACY/ERP y binding can?nico con ?ndice ?nico parcial independiente de active. V1?V9 intactas; sin borrado/reparaci?n de legacy, Banner ni hist?ricos.
- Sync JDBC at?mica crea categor?as y fichas UUID en borrador; reconcilia incluso revisi?n igual. Nunca adopta bindings previos por identidad editorial o semejanza: crea principal nuevo si falta, conserva todos los hist?ricos. Slugs estables y colisiones con sufijos; bloqueo com?n con escritores admin. ERP mueve la relaci?n de categor?a; no reescribe presentaci?n. Product DynamicUpdate evita sobrescribir categor?a por un cambio editorial concurrente.
- P?blico exclusivamente ERP can?nico, activo, AVAILABLE, soportado y publicado editorialmente; categor?as derivadas de fichas visibles. Baja/reaparici?n conserva todo. Admin protege estructura/t?cnica, conserva presentaci?n. Campos t?cnicos legacy nulos en ERP; DTO suma catalogMode/canonical/erpCategoryId seg?n recurso. Sin tarifas locales. Contrato y transici?n en ERP_CATALOG.md.
- Nuevos env?os legacy rechazados 409; replay confirmado v1/v2, snapshots y exportaciones intactos. Tests HTTP antiguos adaptados a la nueva pol?tica; integraci?n PostgreSQL nueva con fixtures sint?ticos/rollback preparada. V10/Flyway, restricciones SQL y HTTP con PostgreSQL pendientes por ausencia de TEST_DB_PASSWORD; no se busc? el secreto.
- Validaci?n inicial: 28 pruebas focalizadas; ampliaci?n 36; regresi?n Java 21/Maven Wrapper offline: 147 tests, 0 fallos/errores, 2 omisiones POSIX preexistentes. Una selecci?n intermedia por wildcard incluy? accidentalmente QuoteRequestApiTest: 14 errores de arranque por autenticaci?n TEST sin credencial, sin conexi?n obtenida ni migraciones. Se interrumpi? y corrigi? con exclusiones expl?citas.
- Comando de regresi?n: `./mvnw.cmd -o -f .tmp/erp-editorial-build/pom.xml '-Dtest=*Test,!AdminApiTest,!CatalogApiTest,!QuoteRequestApiTest,!ProductionAdminBootstrapTest' test`. POM temporal ignorado usa fuentes/recursos originales y salida aislada del editor; pom.xml versionado intacto.
- Cierre: 31 pruebas focalizadas finales aprobadas tras dos casos nuevos de sync y exposici?n de identidad de categor?a en admin. `-DskipTests package` BUILD SUCCESS, compila tambi?n las pruebas IT; artefacto `.tmp/erp-editorial-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar`. `git diff --check` correcto; sin ejecuci?n real de Flyway.
- Limitaciones: sync manual sin TTL/scheduler, listado sin paginaci?n, categor?as vac?as no disponibles en contrato ERP, frontend no adaptado a edici?n ERP con campos t?cnicos nulos. El primer despliegue requiere migraci?n + sync + aprobaci?n editorial; legacy deja de ser p?blico por dise?o.


## Correccion del bean de seguridad de exportacion

- Sobre c1f89cf, se renombro unicamente el metodo @Bean a requestExportSecurityFilterChain: el nombre anterior requestExportSecurity colisionaba con el componente @Configuration descubierto por scanning. Seguridad, propiedades y permisos intactos; overriding no habilitado.
- Validacion focalizada: RequestExportSecurityTest 4/4; despues RequestExportContextTest 1/1 con component scan y setAllowBeanDefinitionOverriding(false). Contexto web minimo inicia y contiene una configuracion y una cadena de exportacion. Sin DB/Flyway/ERP; no acredita arranque integrado con PostgreSQL. Las pruebas anteriores con @Import no reproducian el nombre del componente escaneado.

## Fase 2B.6.1: exportacion tecnica READ-ONLY - 2 de octubre de 2026

- Inicio: `feature/solicitudes-web`, Git limpio. Solo MIQA backend; sin ERP, frontend, sincronizacion, ACK/outbox, cambios comerciales, push o cotizaciones. Sin git add/commit/push/merge/deploy/cambio de rama.
- GET `/api/integracion/erp/v1/solicitudes` y `/{id}`: contrato v1, sourceSystem MIQA_STORE, cabeceras paginadas sin PII y detalle con contacto/notas/items historicos. Lee ambas tablas de items, soporta LEGACY incluso dentro de v2 y ERP v2 con/sin pricing. Exporta DTOs conocidos, nunca JSON crudo, idempotency key ni hash; no consulta catalogo vivo ni inventa IDs ERP legacy.
- Seguridad entrante nueva y aislada, reutilizando header X-ERP-Service-Key: `MIQA_ERP_REQUESTS_API_KEY` independiente de la clave saliente y `MIQA_ERP_REQUESTS_SCOPES=solicitudes:read`. Vacias por defecto: acceso cerrado. Solo GET; no-store tambien en errores, sin logs de contacto/cuerpos/claves. JWT/admin/catalogo/precios intactos. El helper TEST actual bloquea variables MIQA_*; su allowlist/aprovisionamiento de prueba se debe revisar antes de usar ese helper con esta credencial.
- Cursor Base64URL versionado con ventana createdFrom inclusivo/createdBefore exclusivo, limit y ultima tupla `(createdAt,id)`; orden SQL ASC con id COLLATE C, sin OFFSET/referencia como cursor. Limite 1-100, default 50. Transacciones readOnly REPEATABLE_READ por peticion. No es un watermark de commits: consumidor debe upsert por sourceSystem+id y reconciliar ventanas/historial completo periodicamente para commits tardios. Paginas repetibles por parametros/IDs, no snapshots inmutables entre peticiones. Contrato exacto y limites en [REQUEST_EXPORT_V1.md](REQUEST_EXPORT_V1.md).
- Validacion: primero 14 pruebas focalizadas aprobadas. Despues UNA pasada de suite relevante y package Java 21/Maven Wrapper offline: **140 tests, 0 fallos, 0 errores, 2 omitidos POSIX preexistentes**; 138 aprobados. Salida aislada `.tmp/phase2b61-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar`; pom.xml intacto. Pruebas de seguridad real, permisos, SQL/keyset, orden/limites/reconciliacion, detalle legacy/v2/precios, inexistentes y privacidad. Cuatro clases dependientes de PostgreSQL excluidas explicitamente por falta de TEST_DB_PASSWORD; sin intento de conexion a DB/DEV/PROD ni Flyway. V1-V9 intactas, sin migraciones nuevas. RequestExportPersistenceIT compilado, pendiente en TEST tras V9; cubre commits fuera de orden con dos conexiones y ambas tablas historicas. `git diff --check` correcto.

## Fase 2B.5: evaluacion de precios ERP - 30 de septiembre de 2026

- Inicio verificado: `feature/solicitudes-web`, Git limpio. WORKFLOW.md no encontrado; no creado. Trabajo exclusivo MIQA backend; ERP solo inspeccionado en fuentes del contrato de precios, sin cambios ni llamadas DEV/PROD. Frontend intacto. Sin commit, staging, push, merge, deploy o cambio de rama; sin lectura de secretos ni cambios a application*.properties.
- Nuevo POST `/api/public/pricing/evaluate`: productId, quantity, erpMaterialId/erpModelId y measures. Reconstruye servicio/revisiones desde publicacion, binding activo y proyeccion AVAILABLE; reutiliza validacion estructural de 2B.4. Sin formulas, recargo IGV ni cache persistente. DTO comercial con PRICE_AVAILABLE / QUOTE_REQUIRED / CONFIGURATION_STALE / CONFIGURATION_INVALID / TEMPORARILY_UNAVAILABLE; HTTP 200/200/409/422/503. Producto oculto/inexistente 404. 401/403/5xx/timeout y contrato roto nunca se convierten en POR_COTIZAR.
- Cliente ERP existente ampliado con ruta POST fija y mismas variables externas. Clave solo en header backend, sin redirects, URL restringida, timeout total 20 s y respuesta acotada; errores sanitizados sin causas/cuerpos. No se publican revisiones/diagnosticos. Pruebas HTTP con clave sintetica verifican header, respuesta, snapshot, logs y rechazo de reflejo literal del secreto. Preview con no-store, CORS explicito, 64 KiB y limite propio 120/minuto/instancia, independiente del envio de solicitudes.
- Quote v2: mismo input/hash/idempotencia/referencia. Primero recupera solicitudes resueltas sin ERP; despues prepara snapshots en lectura corta REPEATABLE_READ, evalua ERP sin transaccion y abre escritura corta con segunda comprobacion de idempotencia y relectura local contra cambios durante HTTP. Cabecera/items all-or-nothing; recuperacion de colision conservada. LEGACY no llama ERP; mixtos conservan orden. Stale/invalid/temporal rechazan el conjunto. El snapshot ERP suma pricing historico con importe, PEN, IGV incluido, alcance, forma, base facturable, pricingRevision y evaluatedAt; POR_COTIZAR queda sin importe. No se reescriben snapshots anteriores.
- V1-V9 sin cambios, sin migracion nueva. No se aplico Flyway. El primer intento de suite amplia incluyo accidentalmente ProductionAdminBootstrapTest (perfil TEST), que intento conectar exclusivamente a TEST y fallo por autenticacion: TEST_DB_PASSWORD no disponible. No obtuvo conexion ni ejecuto migraciones. Suite completa/PostgreSQL/Flyway no validados en esta entrega; no se buscaron credenciales ni se redirigio a otra base.
- Validacion final: Java 21, Maven Wrapper offline, **package BUILD SUCCESS; 126 tests, 0 fallos, 0 errores, 2 omitidos** (pruebas preexistentes de permisos POSIX no disponibles en Windows). 124 aprobados. Excluidas explicitamente AdminApiTest, CatalogApiTest, QuoteRequestApiTest y ProductionAdminBootstrapTest por dependencia PostgreSQL. Cobertura de precios/transporte real simulado/timeout/CORS, regresion sync/admin/catalogo/v1/v2, idempotencia, rollback y cambios durante HTTP. PricingSnapshotPersistenceIT compilado y preparado para TEST tras V9, no ejecutado; fixtures sinteticos y rollback, sin Boot/Flyway/ERP.
- La salida target presento clases con errores de compilacion en runtime pese a javac correcto; se valido en directorio aislado sin detener procesos del editor. Artefacto comprobado: `.tmp/phase2b5-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar` (66 884 081 bytes). POM de validacion ignorado apunta a las mismas fuentes/recursos y fija workingDirectory del test al proyecto; pom.xml versionado intacto. Evidencia resumida en `.tmp/phase2b5-build.log`. `git diff --check` correcto.
- Contrato, estados, limites y comandos exactos de validacion por el propietario (ERP DEV 8080 / MIQA TEST 8081 / PostgreSQL TEST 55432) en [PRICING_2B5.md](PRICING_2B5.md). Pendientes: persistencia JSONB/Flyway real y caso manual de referencia 105.00 PEN/3.0 M2. Ese importe no esta hardcodeado. No hay transaccion distribuida/reserva de tarifa; items se evaluan secuencialmente y pueden tener revisiones/fechas distintas.

## Fase 2B.4: configuración pública y solicitudes v2 — 30 de septiembre de 2026

- Partida verificada: backend limpio en `feature/solicitudes-web`, HEAD `eefca0d`; frontend limpio en la misma rama, HEAD `f358f91`. El propietario confirmó prueba real de 2B.2 y binding Banner → ERP 1 mediante 2B.3; no se repitió esa conexión.
- Product público suma configuration LEGACY/ERP/UNAVAILABLE desde binding/proyección local. No llamadas ERP por visita ni exposición de claves; Admin sigue protegido. Sin binding conserva legacy; binding inactivo/no disponible/inválido bloquea configuración sin ocultar la publicación ni caer a reglas legacy.
- Nuevo POST `/api/public/quote-requests/v2` para ítems ERP o mixtos. Snapshot histórico ERP v2 con nombres autoritativos, revisiones, material/modelo, cantidad decimal, medidas y configuración. Snapshot/POST/hash v1 preservados; nuevos envíos legacy de un Product vinculado se rechazan con 409 para evitar evasión. Reintentos ya confirmados se recuperan antes de revalidar catálogo.
- V9 preparada, **NO ejecutada**: tabla independiente quote_request_v2_items, orden original y snapshots v1/v2; cabecera/secuencia/idempotencia compartidas en quote_requests. V1–V8 intactos, sin reescritura histórica. Sin Product eliminado, migración masiva, precios o envío a ERP. Lecturas y persistencia del POST en transacción REPEATABLE_READ.
- Validación: **106 tests backend aprobados**, cero fallos/errores/omitidos; frontend **155/155** y build offline correcto; Edge local simulado con 8 auditorías axe sin infracciones a 390/1440 px. Sin DB/Flyway/ERP/producción. Empaquetado habitual bloqueado por rename del JAR existente; paquete exitoso en `.tmp/phase2b4-build/target/miqa-store-backend-0.0.1-SNAPSHOT.jar`, sin detener procesos ajenos.
- Pendiente V9/manual TEST y prueba real de persistencia PostgreSQL de v2. Contrato, decisiones, límites dimensionales MIQA y lectura futura de ambas tablas en [QUOTE_REQUEST_V2.md](QUOTE_REQUEST_V2.md). No commit/push/merge/deploy, staging o cambio de rama; ERP/GoPrint intactos.

## Fase 2B.2: proyección ERP y vínculos — 29 de septiembre de 2026

- Implementación local en `feature/solicitudes-web`, sin commit/staging/push/merge/deploy ni cambio de rama. ERP inspeccionado solo en fuentes del contrato v1; sin cambios ni llamadas al ERP, GoPrint, frontend o producción. No se leyeron ni modificaron secretos existentes o application-dev.properties.
- Nuevo paquete `com.miqa.store.erp`: contrato permitido v1, cliente HTTP backend con clave externa, validación completa previa a escrituras, persistencia JDBC transaccional, sincronización manual y endpoints bajo `/api/admin/erp-catalog`. Seguridad JWT administrativa existente, sin scheduler ni llamadas ERP desde catálogo público.
- V8 `V8__erp_catalog_projection.sql` preparada, **NO ejecutada**. Crea proyección JSONB con estado local, vínculo independiente (PK Product, ID ERP no único) y resultado de sincronización singleton. Product/categorías MIQA y solicitudes Fase 1/schemaVersion=1/V1–V7 intactos. No precios ni tarifas.
- Un vínculo actual por Product, incluso desactivado; reasignación actualiza la fila bajo bloqueo del Product. Varios Product pueden compartir servicio ERP. FK RESTRICT conserva referencias. Estado diagnóstico derivado de active y disponibilidad de la proyección.
- Sincronización serializada mediante advisory lock transaccional PostgreSQL; HTTP y lista validados antes de reconciliar. catalogRevision igual conserva payload/evaluatedAt y solo refresca lastSyncedAt/disponibilidad. Ausentes quedan PENDING_REVALIDATION sin borrado; último contrato permanece como histórico. Error ERP no desactiva proyecciones ni borra succeededAt. Fallo de escritura revierte toda la transacción.
- Configuración: `app.erp.base-url` / `ERP_TIENDA_VIRTUAL_BASE_URL`; `app.erp.api-key` / `ERP_TIENDA_VIRTUAL_API_KEY`. HTTPS remoto/HTTP loopback, sin redirects; conexión 5 s, solicitud 20 s, cuerpo máximo 10 MiB. Clave solo en header técnico, nunca DTO/DB/log. Sin configuración el intento manual responde 503.
- Validación comprobada: Java 21 y Maven 3.9.9 offline, **clean package BUILD SUCCESS; 87 tests, 0 fallos, 0 errores, 0 omitidos**. Incluye 17 tests nuevos de contrato/sync/cliente HTTP simulado/seguridad real sin Boot DB, y regresiones Phase 1, Product/admin y configuración/aislamiento. Maven Wrapper falló en el entorno; se usó Maven instalado con caché local explícita. Se compiló toda la aplicación y todos los tests y se generó el JAR. `git diff --check` correcto.
- No se ejecutó la suite completa histórica porque arranca Flyway. `ErpCatalogPersistenceIT` (3 pruebas) queda compilada y preparada para ejecutar explícitamente **después de aplicar V8 manualmente en TEST**; usa destino fijo TEST, fixtures propios y rollback, sin Flyway/DDL. La validación real de constraints/SQL en PostgreSQL, migración V8 y prueba contra ERP DEV siguen pendientes. No se abrió ninguna conexión a DB ni se aplicaron migraciones.
- Diseño, endpoints, semántica de timestamps/histórico, comandos y limitaciones en [ERP_CATALOG.md](ERP_CATALOG.md). No hay caducidad automática ni historial de reasignaciones de vínculos; no se infieren motivos ERP de las ausencias. Los contadores changed excluyen refrescos de fecha. Un fallo de DB conserva el estado del intento anterior y produce error interno sanitizado.

## Fase 1: integración local real validada — 28 de septiembre de 2026

- Solicitud Web persistente implementada. El propietario confirmó la validación local real del **2026-09-28**: Angular `localhost:4200` → backend TEST `localhost:8081` → PostgreSQL TEST `127.0.0.1:55432/miqa_store_test_db` → solicitud persistida → referencia devuelta al frontend → WhatsApp preparado después de persistir.
- La prueba generó `MIQA-000017` únicamente en TEST. Se comprobaron `RECIBIDA` / `TIENDA_VIRTUAL`, Roll Up, `QUANTITY`, cantidad **5** y snapshot histórico JSONB persistido. No se incluyen datos personales de la prueba.
- Idempotencia cubierta por tests: misma clave y contenido devuelven la misma solicitud/referencia sin duplicados; contenido diferente con la misma clave responde 409 sin modificar la original ni crear otra.
- La recuperación de intentos pendientes frontend usa `sessionStorage`, limitada a la sesión/pestaña correspondiente. Precios, mapeo ERP, bandeja ERP y conversión a cotización/OT siguen pendientes, al igual que protección por cliente/proxy y política de acceso/retención antes de operación pública.
- Esta actualización es documental: no repite tests ni accede a bases de datos. Las validaciones históricas siguientes se conservan; sus pendientes de integración Angular/WhatsApp quedan supersedidos por esta confirmación. No implica despliegue ni acceso a DEV/PROD/VPS/ERP.

## Arranque manual TEST para Angular — 28 de septiembre de 2026

- `scripts/Start-TestApp.ps1` resuelve la ausencia de recursos TEST en el classpath de `spring-boot:run`: carga explícitamente mediante `SPRING_CONFIG_LOCATION` los archivos existentes main/application.properties y test/application-test.properties. Sin duplicar configuración JWT/datasource ni incluir clases de tests en runtime.
- Perfil test, servidor loopback:8081, DB exclusiva `127.0.0.1:55432/miqa_store_test_db` como `miqa_store_local`, CORS localhost:4200 y clave JWT exclusivamente TEST del archivo existente. Contraseña externa `TEST_DB_PASSWORD`, prompt oculto si falta, nunca archivo/argumento/log. Media manual separada en `.local/test-app-media`; variables/directorio restaurados en finally.
- Antes de lanzar Maven rechaza overrides heredados Spring/servidor/DB/JWT/logging/media, opciones JVM/Maven y configuración adicional `.mvn` no vacía. `LocalDatabaseGuard` intacta como validación final anterior a DataSource/Flyway. V1–V7, pom y properties sin cambios. El helper no inicia PostgreSQL ni crea usuarios; Spring ejecuta Flyway normal solo contra TEST.
- Validación: **28 tests Java aprobados (0 fallos/errores)**: ManualTestConfigurationTest (2), TestDatabaseIsolationTest (12), ConfigurationTest (7), ProductionConfigurationTest (7). Carga ConfigData real sin beans ni JDBC y rechazos de DB DEV/PROD/ERP/remotas. **26 comprobaciones del launcher aprobadas** con Maven simulado, sin Java/DB/red; incluye rutas con espacios, overrides, prompt simulado y limpieza tras fallos.
- Durante la validación del launcher, `TEST_DB_PASSWORD` no estaba disponible en la sesión del agente; no se intentó obtenerla de archivos ni se abrió conexión a ninguna BD. La integración local real fue confirmada posteriormente por el propietario, como se registra arriba. Los 123 tests anteriores siguen siendo evidencia histórica, no repetida aquí; no se infiere de ellos la ejecución específica del helper.
- Comando: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-TestApp.ps1` cuando la política de Windows impide `.\scripts\Start-TestApp.ps1`; Bypass afecta únicamente al nuevo proceso. Detalles en README. Sin commit/push/merge/deploy, cambio de rama, DEV/PROD/VPS/ERP ni cambios al trabajo frontend pendiente.

## Fase 1: backend validado en PostgreSQL TEST — 28 de septiembre de 2026

- Estado vigente: implementación de Solicitudes Web revisada en `feature/solicitudes-web`. Se creó PostgreSQL TEST local aislado en `127.0.0.1:55432/miqa_store_test_db`, usuario `miqa_store_local`, según la validación manual confirmada por el propietario. Contraseña externa, nunca documentada ni versionada.
- Con `SPRING_PROFILES_ACTIVE=test`, `./mvnw.cmd test` terminó en **BUILD SUCCESS: 123 tests, 0 failures, 0 errors, 2 skipped**. Los reportes locales Surefire corroboran los totales; `QuoteRequestApiTest` pasó 14 tests y `TestDatabaseIsolationTest` 12. No se repitió la suite durante la revisión final porque solo se actualizó documentación.
- Flyway validó siete migraciones y aplicó correctamente **V1–V7 desde esquema vacío**, dejando TEST en versión v7, según la ejecución confirmada. V7 quedó validada; V1–V6 no se modificaron. No se aplicó V7 fuera de TEST.
- POST público con contacto obligatorio, clave UUID obligatoria, referencia por secuencia, idempotencia concurrente y snapshot histórico transaccional. V7 no tiene FK hacia el catálogo vivo ni columnas de precios. Cantidades manuales exactas, PACK y AREA conservan su contrato; límites de 50 ítems y 64 KiB, CORS explícito, administración con JWT y respuesta sin PII.
- Revisión final sin hallazgos bloqueantes ni cambios de comportamiento. DEV, PROD y ERP no fueron tocados; sin VPS, push, deploy ni cambio de rama. El cierre se limita a un commit local del backend.
- Pendientes: envío Angular, WhatsApp posterior a persistencia, limitación por cliente mediante proxy confiable y política de acceso/retención de contacto. El límite actual es global por instancia (120 POST/minuto), no por IP. La Fase 1 completa sigue pendiente. La ejecución específica del helper TEST no se deduce del resultado de Maven: se conserva su validación estática documentada.

Las secciones siguientes son antecedentes; sus pendientes de PostgreSQL/V7 quedan supersedidos por esta validación.

## Aislamiento exclusivo TEST — 28 de septiembre de 2026

- Se conservan los cambios locales de Solicitudes Web en `feature/solicitudes-web`. Esta tarea solo endurece TEST y prepara su procedimiento; no ejecuta pruebas integradas ni modifica V7 o lógica de solicitudes.
- `LocalDatabaseGuard` continúa como EnvironmentPostProcessor registrado antes de DataSource/Flyway y después de ConfigData. TEST exige URL exacta `127.0.0.1:55432/miqa_store_test_db`, usuario `miqa_store_local`; valida URL datasource/Hikari/Flyway mediante Binder y rechaza JNDI, clases/pools/drivers alternativos y mapas de propiedades JDBC. Perfiles no-test conservan sus reglas. Errores sanitizados sin valores ni causas sensibles.
- `application-test.properties` fija host/puerto/base/usuario. Solo la contraseña se obtiene de `TEST_DB_PASSWORD`. Variables genéricas DB_* y las antiguas TEST_DB_PORT/USERNAME no redirigen TEST.
- Nuevo `scripts/Start-TestPostgres.ps1`, preparado pero NO ejecutado: clúster separado `.local/postgres-test/data`, loopback:55432, marca TEST, contraseña oculta/env, creación solo con `-CreateDatabase`. No reutiliza el clúster compartido ni configura otras bases de aplicación; createdb necesita la base de mantenimiento postgres para crear exclusivamente TEST. Sin migraciones ni tests automáticos. Ver procedimiento y límites en README.
- Validación: 26 pruebas unitarias de aislamiento/configuración aprobadas, sin arrancar Spring o DB; parseo estático del script. Compilación y package con tests omitidos aprobados. Integración HTTP/PostgreSQL y ejecución del script siguen pendientes.
- PostgreSQL NO iniciado, Flyway NO ejecutado, V7 NO aplicada. Sin DEV/PROD/ERP, commit, push, deploy ni cambio a main. La Fase 1 sigue incompleta; no reinterpretar las validaciones históricas siguientes como pruebas de V7.

## Corrección de eliminación de categorías y contexto JPA — 17 de septiembre de 2026

- El DELETE de una categoría vacía podía fallar al crear el tombstone de su slug y desvincularlo mediante un bulk UPDATE en la misma transacción. El UPDATE modificaba PostgreSQL, pero dejaba el `CategorySlugAlias` administrado apuntando en memoria a la categoría que se eliminaba; el flush posterior de Hibernate detectaba esa referencia obsoleta y Spring la traducía a `InvalidDataAccessApiUsageException`.
- `CategorySlugAliasRepository.detachCategory` conserva su JPQL y parámetros, y ahora usa `@Modifying(flushAutomatically = true, clearAutomatically = true)` para limpiar el persistence context después del bulk UPDATE. No se cambió Hero, Flyway ni datos manualmente.
- Se añadió cobertura HTTP/PostgreSQL específica para eliminar una categoría vacía nunca renombrada, obligando a crear y desvincular el tombstone en la misma transacción. La prueba Mockito anterior solo verificaba invocaciones y no ejecutaba Hibernate, el bulk UPDATE ni un flush real.
- Validación de esta sesión: `AdminCatalogServiceDeletionTest` aprobó 2/2 pruebas. La prueba HTTP/PostgreSQL específica no pudo iniciar porque `miqa_store_test_db` no escuchaba en `127.0.0.1:55432`; no se redirigió a DEV. DEV no se declaró funcional y debe repetirse el DELETE después de reiniciar el backend con este código.

## Categorías públicas dinámicas e historial SEO — 17 de septiembre de 2026

- `categories.active` se reutiliza como publicación/indexabilidad; no se agregó un estado redundante. La API pública devuelve únicamente activas y sus productos publicados. Admin conserva crear, editar y activar/desactivar, y suma eliminación de categorías vacías.
- El backend es autoridad exclusiva del slug de categoría. `CategorySlug.fromName` normaliza minúsculas, tildes/diacríticos y ñ, agrupa separadores como un guion y recorta extremos. `CategoryInput` ya no acepta slug. Crear siempre calcula el slug; editar solo lo recalcula si el nombre almacenado cambia. Guardar contenido editorial/estado con el mismo nombre conserva slugs legacy aunque difieran de la normalización actual. Un resultado igual no crea historial y cualquier colisión devuelve 409 sin sufijos automáticos.
- V6 crea `category_slug_aliases`: al cambiar slug guarda el anterior ligado a la categoría; al eliminar una categoría vacía conserva aliases y slug actual como tombstones con `category_id NULL`. Los slugs actuales de categorías/productos y aliases históricos se validan de forma cruzada en escrituras administrativas. Una categoría con productos no puede eliminarse.
- `GET /api/public/category-slug-redirects` devuelve `oldSlug -> currentSlug` solo para categorías activas. El frontend usa este contrato durante build para generar reglas 301 de Cloudflare; la API no pretende que una navegación JavaScript sea una redirección HTTP.
- Validación disponible sin DB: compilación Java 21 correcta y 9/9 tests de normalización/configuración. La suite completa compiló 54 tests pero no pudo ejecutarse porque el PostgreSQL aislado de tests no escuchaba en 127.0.0.1:55432 y no se encontraron binarios locales para iniciarlo; no se usó DEV ni producción.

## Preflight CORS para DELETE administrativo — 16 de septiembre de 2026

- La configuración CORS administrativa omitía `DELETE` de `allowedMethods`; por ello Spring rechazaba con 403 el preflight antes de que el DELETE autenticado alcanzara el controller. Se añadió únicamente `DELETE` a los métodos permitidos de `/api/admin/**`, reutilizando los origins explícitos existentes y conservando headers, credenciales y tiempo de caché.
- Spring Security mantiene CORS habilitado y `/api/admin/**` autenticado. Los preflight válidos se resuelven por el filtro CORS sin Bearer; el DELETE real continúa requiriendo JWT. No se abrió ningún origin ni endpoint, y no se cambió CSRF, datasource, Flyway o producción.
- El test HTTP CORS administrativo cubre preflight DELETE/POST/PUT desde el origin configurado, headers `Access-Control-Allow-Origin` y `Access-Control-Allow-Methods`, rechazo de origin ajeno y DELETE real sin autenticación.
- Validación local con Java 21: `ProductionConfigurationTest` aprobó 7/7 pruebas sin base y `mvn -DskipTests package` terminó en BUILD SUCCESS, compilando también los tests. La prueba HTTP y la suite completa no se ejecutaron: su URL está fijada a `127.0.0.1:55432/miqa_store_test_db`, pero el puerto no escuchaba y no había credenciales TEST cargadas; no se usó producción. `git diff --check` correcto. Sin commit, push ni deploy.

## Eliminación administrativa de materiales — 16 de septiembre de 2026

- `product_materials` es una tabla de opciones pertenecientes a un único producto mediante `product_id NOT NULL`; no es un catálogo global y ninguna otra tabla la referencia. Se añadió `DELETE /api/admin/products/{productId}/materials/{materialId}` con la autenticación Bearer administrativa existente. Comprueba primero el producto, exige que el material pertenezca a ese producto, elimina físicamente solo esa fila y devuelve 204. Producto/material inexistente o asociación incorrecta devuelven el 404 administrativo existente.
- La eliminación es física porque el material no tiene referencias ni historial dependiente y el objetivo es retirar opciones erróneas; la baja reversible mediante `active` se conserva. No se modificó ningún dato existente, incluido el registro `"."`, ni se creó migración Flyway.
- Creación y edición de materiales normalizan el nombre con `trim`, rechazan null/vacío/solo espacios mediante Bean Validation y ahora exigen al menos una letra o número Unicode. Nombres únicamente de puntuación como `.`, `,`, `-` y `_` devuelven 400; se admiten nombres comerciales con paréntesis, medidas, acentos, `+` y guiones.
- Se agregaron pruebas HTTP para autenticación, 204, producto/material inexistentes, ownership, permanencia de productos/materiales ajenos y validación/normalización de nombres. No se ejecutaron porque el perfil test fija `127.0.0.1:55432/miqa_store_test_db`, pero ese PostgreSQL no estaba escuchando y no había credenciales TEST cargadas; nunca se sustituyó por `miqa_store_db` ni por el túnel de producción.
- Java 21 y `mvn -DskipTests package` correctos, incluyendo compilación de fuentes y tests; JAR generado. El `mvnw.cmd` local falló antes de iniciar Maven por un problema del script wrapper al evaluar `.m2`, por lo que se usó la distribución Maven 3.9.9 ya descargada por el mismo wrapper. `git diff --check` correcto. Sin commit, push ni deploy.

## Cabeceras de categoría administrables — 15 de septiembre de 2026

- V5 agrega `catalog_headline` (200) y `catalog_description` (500) opcionales a `categories`, con restricciones de texto plano, e inicializa las seis categorías por slug. La entidad y los DTO públicos/admin exponen `catalogHeadline` y `catalogDescription`; el guardado administrativo normaliza espacios y valores vacíos a null.
- Los endpoints existentes de categorías se extendieron sin crear recursos paralelos. Se añadieron pruebas para seed/serialización pública, actualización, trim, límites y rechazo de HTML. Compilación limpia y package sin tests correctos con Java 21. Las suites HTTP/Flyway no pudieron arrancar porque PostgreSQL de tests no escuchaba en 127.0.0.1:55432 y el helper no encontró binarios PostgreSQL instalados; no se usó ninguna otra base.

## Inicio local con DB DEV compartida — 15 de septiembre de 2026

`scripts/Start-Dev.ps1` levanta el backend local contra `miqa_store_dev_db` mediante un túnel SSH que debe estar abierto previamente en `127.0.0.1:5433`. El helper comprueba el puerto antes de solicitar credenciales; si no está disponible, se detiene e indica el comando SSH que debe ejecutarse en otra terminal. No abre el túnel automáticamente ni se conecta directamente al puerto PostgreSQL del VPS.

Desde cualquier PowerShell puede invocarse por su ruta; el script resuelve y cambia a la raíz del proyecto antes de ejecutar Maven:

```powershell
ssh -p 2222 -N -L 5433:localhost:5432 -o ServerAliveInterval=60 root@64.176.22.247
# En otra terminal:
& 'D:\MIQA-STORE\miqa-store-backend\scripts\Start-Dev.ps1'
```

El helper configura solo el entorno del proceso de PowerShell para usar `127.0.0.1:5433`, el rol `miqa_store_dev`, perfil `local`, CORS `http://localhost:4200`, media en `.local/media` y base `http://localhost:8081/media`. Solicita `DB_PASSWORD` de forma oculta y genera un `ADMIN_JWT_SECRET` aleatorio de 32 bytes para cada ejecución; ninguno se imprime ni se guarda y ambos se eliminan del entorno al terminar Spring. `.local/` ya está excluido de Git. El flujo no cambia los helpers de PostgreSQL local, archivos `application*.properties`, Flyway ni producción.

Validación local del helper: análisis sintáctico de PowerShell correcto, configuración/limpieza esperadas presentes y `git diff --check` correcto. No se inició Spring ni se introdujeron credenciales durante la validación.

## Base DEV compartida mediante túnel SSH — 15 de septiembre de 2026

`LocalDatabaseGuard` permite, con el perfil de desarrollo, `miqa_store_dev_db` además de `miqa_store_db` y `miqa_store_test_db`. El desarrollo compartido puede alcanzarse mediante un túnel SSH: el JDBC del backend sigue usando `localhost` o `127.0.0.1` porque el túnel termina localmente. El perfil `prod` continúa restringido exclusivamente a `miqa_store_db` y rechaza `miqa_store_dev_db`; `miqa_store_test_db` permanece reservado para tests. No se modificaron `application-prod.properties`, Flyway ni las migraciones.

## Upload físico de imágenes — 14 de septiembre de 2026

Trabajo LOCAL, sin commit, push, deploy ni acceso a producción. Al inicio ambos repositorios estaban limpios. Según el propietario, API https://api-store.solucionesmicaela.com y Nginx/media ya funcionan en producción; esas confirmaciones reemplazan los pendientes históricos de despliegue de las secciones inferiores. Esta nueva implementación todavía NO se ha desplegado.

API administrativa nueva, protegida por el Bearer existente:
- POST /api/admin/products/{pid}/images/upload, multipart/form-data: file obligatorio; altText opcional (hasta 300 caracteres), displayOrder opcional (entero no negativo), primaryImage opcional (false). Devuelve ImageView y HTTP 201.
- POST /api/admin/products/{pid}/images/{id}/remove: HTTP 204, baja lógica, sin DELETE físico del registro. No endpoint de reactivación.
- GET de imágenes y DTOs públicos/admin solo incluyen activas, ordenadas por displayOrder/id. POST manual y PUT de metadata existentes se conservan; el POST manual también respeta el límite de tres. No permite reemplazar la URL de un upload administrado: quitar y volver a subir.

Máximo tres imágenes activas por producto, incluyendo legacy; bloqueo PESSIMISTIC_WRITE del producto compartido entre creación manual, upload, principal y baja. Prueba de dos uploads simultáneos demuestra 201/409 al competir por la tercera plaza. Primera imagen sin principal se convierte en principal; primaryImage=true desmarca la anterior. Quitar la principal promueve la primera restante por displayOrder/id. Sin orden explícito, usa máximo existente + 1, con protección de overflow.

Formatos: image/jpeg (.jpg/.jpeg), image/png (.png), image/webp (.webp), hasta 5 MiB (5 MB en UI). Se comprueba MIME/extensión y firma del contenido; JPEG/PNG además verifican dimensiones mediante ImageIO sin decodificar el bitmap. WebP comprueba contenedor RIFF/WEBP, longitud y tipo de chunk, sin conversión ni decodificador adicional. Sin antivirus, reencoding ni conversión automática.

ProductImageStorage genera products/{productId}/{uuid}.jpg|png|webp bajo MEDIA_STORAGE_PATH, configurado en producción como /opt/miqa-store/media. Guarda URL absoluta basada en MEDIA_BASE_URL (https://api-store.solucionesmicaela.com/media). Upload requiere base URL no vacía; responde 503 legible si falta. No endpoint de entrega local nuevo: el servidor/proxy de media configurado entrega los archivos; no se modificó Nginx/Cloudflare.

V4__product_image_upload.sql agrega active (true por defecto), storage_key nullable y constraint que impide principal inactiva. V1/V2/V3 y cinco productos seed intactos. storage_key solo se asigna por el servidor y no se expone en DTO. Quitar un upload elimina su archivo después de confirmar la transacción; rollback de upload elimina el archivo nuevo. Las claves admitidas son estrictas y se rechazan traversal y symlinks en ancestros. Nunca se deriva un archivo a borrar de url ni del nombre original; legacy/URLs manuales tienen storage_key null. Si el filesystem falla durante cleanup, se registra aviso genérico sin rutas y puede quedar un archivo huérfano que requiere revisión local del storage; no hay recolector automático.

MediaProperties preserva /images/... aunque MEDIA_BASE_URL esté configurada. Las referencias legacy no se migran ni se borran. URLs HTTP(S) absolutas se conservan. Frontend mantiene STORE_API_CONFIG.mediaBaseUrl vacío.

Errores: 400 vacío/tipo/contenido/metadatos inválidos; 413 tamaño; 404 producto/imagen ajenos o ausentes; 409 límite. Multipart máximo 5MB por archivo y 6MB por request. Respuestas no revelan paths internos.

Validación Java 21: Maven Wrapper test, 45 tests, 0 fallos, 0 errores, 0 omitidos; PostgreSQL exclusivamente miqa_store_test_db en 127.0.0.1:55432, storage @TempDir. Incluye JPG/PNG/WebP, MIME/contenido/extensión, tamaño/vacío, principal/orden/URL, límite manual y concurrente, autenticación, ownership, baja/legacy, traversal y rollback sin huérfanos. Flyway validó V1–V4 y Hibernate validate correcto. Package con -DskipTests tras suite completa aprobada genera target/miqa-store-backend-0.0.1-SNAPSHOT.jar. No aplicación de V4 en producción ni en miqa_store_db de desarrollo durante esta tarea.

Frontend: upload con preview/validación, tarjetas y detalle con hasta tres imágenes, puntos manuales y visor nativo accesible compartido. Pruebas de navegador usan respuestas interceptadas localmente; no son prueba integrada de despliegue. PostgreSQL aislado local queda disponible; procesos temporales de API de tests/navegador/servidor de revisión se cerraron.


## Ajuste a auditoría real VPS — 14 de septiembre de 2026

Estado vigente: preparación exclusivamente local. No conexión VPS, deploy, git add, commit, push, Cloudflare, SSH, frontend ni cambios ERP/LaserMonitor. Git inicial limpio; cambios de esta tarea sin staging. Esta sección actualiza las conclusiones previas.

Datos confirmados por auditoría del propietario: Ubuntu 26.04 LTS x86_64, 1 vCPU, RAM 1.6 GiB, swap 4.8 GiB, 29 GB libres. Java OpenJDK 21.0.12 global /usr/bin/java; Nginx 1.28.3; PostgreSQL 18.6 únicamente localhost:5432, max_connections=100 y TCP scram-sha-256. UFW activo incoming deny. ERP gigantografias.service / api.solucionesmicaela.com:8080 y LaserMonitor lasermonitor-backend.service / laser-api.solucionesmicaela.com:8081 no se tocan.

MIQA prod 127.0.0.1:8082 en perfil, guarda Java, Nginx y documentación. Local permanece 127.0.0.1:8081; tests HTTP aleatorios y DB local miqa_store_test_db:55432. DB productiva prevista miqa_store_db, rol miqa_store_app, loopback:5432. Sin apertura UFW para API/PostgreSQL. Frontend store.solucionesmicaela.com sigue en Cloudflare Workers Static Assets, no VPS.

Se conservan DB_* externas, JWT obligatorio, CORS APP_CORS_ALLOWED_ORIGINS exacto HTTPS, MEDIA_STORAGE_PATH/BASE_URL configurables, Hibernate validate, Flyway enabled/clean-disabled y Hikari 5/1. V1/V2/V3 sin diff. systemd y bootstrap usan /usr/bin/java -Xms64m -Xmx192m; sin JVM privada ni GC adicional. Heap no equivale a memoria total: medir RSS bajo carga antes del lanzamiento. Bootstrap conserva entrada oculta, no argumentos secretos, perfil explícito sin HTTP/Flyway y BCrypt sin sobrescribir usuarios; implementación ProductionAdminBootstrap intacta.

Nuevas plantillas: deploy/scripts/backup-miqa-store.sh y deploy/systemd/miqa-store-backup.service/.timer. Dump custom -Fc con pg_dump 18, timestamp UTC, archivo parcial/lock, pg_restore --list, fallo explícito y retención 14 días solo tras éxito. Backups root:root 0700/0600, inaccesibles al usuario API. Oneshot root sin capabilities y escritura persistente solo backups, conexión SQL miqa_store_app mediante PGPASSFILE/LoadCredential de backup.pgpass externo 0600; sin JWT ni password hardcodeado. Diario 03:00 UTC más hasta 15 minutos, Persistent=true. No backup/timer ejecutado. .gitattributes conserva LF Bash y añade LF para unidades.

Validación comprobada: Java 21, Maven Wrapper test y package BUILD SUCCESS; ambos 37 tests, 0 fallos/errores/omitidos. Flyway validó 3 migraciones, esquema al día exclusivamente en DB local de tests. Bash -n correcto para bootstrap y backup; git diff --check correcto, índice vacío. JAR target/miqa-store-backend-0.0.1-SNAPSHOT.jar generado. Logs privados ignorados .tmp/vps-audit-tests.log y .tmp/vps-audit-package.log; no publicados. Primer intento de tests interrumpido por PowerShell ErrorActionPreference=Stop al recibir aviso stderr de Mockito; repetición con manejo correcto del exit code Maven pasó, sin cambiar ni omitir tests.

Pendientes: validar unidades/Nginx en Linux, pg_dump/restore real PostgreSQL 18.6 y bootstrap interactivo completo; no realizados en Windows. TLS MIQA por decidir (ERP Origin Certificate, LaserMonitor Let's Encrypt), Cloudflare posterior, media /images y /media aún sin entrega, rate limiting/IP confiable, secretos/recuperación admin y permisos efectivos DB. Backups antes de cerrar despliegue: ensayo restauración, monitorización fallos/espacio y copia cifrada fuera del VPS bajo control propio; dump no respalda roles, configuración/JWT ni media. Guía deploy/README.md incluye comandos exactos futuros de instalación, bootstrap, backup/restauración y Nginx condicionado a TLS, ninguno ejecutado aquí.
## Preparación de producción LOCAL — 14 de septiembre de 2026

Estado vigente: archivos locales para futuro VPS, sin desplegar, crear remotos, commit/push, tocar Cloudflare/DNS, ERP ni PostgreSQL de producción. Frontend intacto: `https://store.solucionesmicaela.com` en Cloudflare Workers/Static Assets. API futura `https://api-store.solucionesmicaela.com`, Java 21/Spring Boot en VPS, bind `127.0.0.1:8082`; DB `miqa_store_db` y media propios, separados del ERP.

`application-prod.properties` requiere DB_* existentes, ADMIN_JWT_SECRET/EXPIRATION, APP_CORS_ALLOWED_ORIGINS, MEDIA_STORAGE_PATH/BASE_URL. Sin fallback de secretos, Hibernate validate, Flyway habilitado/clean deshabilitado, Hikari máximo 5/mínimo 1. Guardas previas al DataSource rechazan base de tests/ERP/remota, prod+local/test, bind público, DDL inseguro, JWT inválido y CORS no HTTPS. Desarrollo local conservado. Proxy headers mediante framework; el proxy confiable debe sobrescribirlos.

Bootstrap explícito `prod,admin-bootstrap`: non-web, Flyway deshabilitado, transacción/bloqueo de admin_users para crear únicamente la primera cuenta BCrypt. No sobrescribe/agrega si ya existe una. Arranque normal no registra este runner. Script Linux con entrada oculta/confirmación y variables efímeras; sin password en history/argumentos. No ejecutado contra producción.

Plantillas systemd, entorno y Nginx bajo deploy/, más guía `deploy/README.md`: usuario dedicado, Java 21, entorno externo root-only, journald y media escribible; nada instalado. Auditoría del propietario confirma Nginx 1.28.3 existente; TLS MIQA por decidir. Actuator solo health agregado, sin detalles/JMX/endpoints sensibles; bloqueado al exterior en ejemplo. Errores inesperados registran solo tipo de excepción, sin SQL/passwords/tokens.

V1/V2/V3 preservadas. V2 mantiene seis categorías/cinco productos como catálogo inicial; imágenes `/images/...` temporales. **MEDIA_BASE_URL del ejemplo aún apunta a un servicio inexistente**: resolver transición/entrega antes de publicar y actualizar referencias por admin/nueva migración. Sin upload/controlador media ni cambios a ProductImage. JAR `target/miqa-store-backend-0.0.1-SNAPSHOT.jar`; nombre estable al copiar para instalación, sin cambiar helpers.

Antes del VPS: inspeccionar OS/proxy/Java/systemd/puertos, TLS Full(strict)/confianza IP Cloudflare, aislamiento/permisos DB/backups, secretos y recuperación de admin, media y rate limiting login. Límite en memoria existente conservado, sin nueva solución improvisada. API base URL frontend cambiará solo cuando API prod funcione. Historial inferior supersedido por esta sección y deploy/README.md para producción.

Validación final Java 21: `mvnw.cmd test` y `mvnw.cmd package` BUILD SUCCESS; 36 tests, 0 fallos/errores/omitidos, solo PostgreSQL local aislado `miqa_store_test_db`. Flyway validó tres migraciones y esquema versión 3 al día; archivos V1/V2/V3 sin diff. Health HTTP devuelve únicamente status UP; endpoints sensibles bloqueados. Pruebas nuevas de configuración prod, JWT/CORS, bootstrap transaccional sin sobrescritura y arranque non-web, sin conexión de producción. Bash `-n` correcto. systemd/Nginx/TLS y bootstrap interactivo completo Linux no ejecutados. Logs ignorados `.tmp/prod-preparation-tests.log` y `.tmp/prod-preparation-package.log`. Git sin staging/commit; frontend limpio, backend sin remoto. JAR ejecutable generado; plantillas requieren revisión de infraestructura antes de instalar.

## Cierre de versionado local ? 13 de septiembre de 2026

Cierre Git LOCAL: repositorio inicializado en main, sin remoto. Se versionan Maven Wrapper, fuentes, scripts, migraciones V1/V2/V3 intactas y documentaci?n. Se excluyen .local/, .tmp/, target/, entornos/secretos locales, logs e IDE. Validaci?n Java 21: 26 tests y package correctos, tambi?n package desde una copia con solo archivos versionables. En producci?n se requerir? Java 21 instalado en el VPS; el JDK portable local no forma parte del repositorio. Sin push ni deploy.


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

Actualizado: 12 de septiembre de 2026. Trabajo exclusivamente local, sin Git inicializado, commit, push ni deploy. Frontend y ERP intactos.

## Trabajo heredado y continuación

Al retomar existían pom.xml (Spring Boot 4.0.8, release Java 21), Maven Wrapper 3.9.9, entidades Category/Product/ProductMaterial/ProductExtra/ProductImage, enum, repositorios, DTOs, servicio/controlador público, CORS, errores y guard local de DB, migrations V1/V2 y scripts PostgreSQL. No había tests, documentación, clúster inicializado ni build validado. Se conservaron esos archivos, corrigiendo BOM UTF-8 que impedía compilar.

La continuación añadió tests reales HTTP/PostgreSQL, MediaProperties y resolución de URLs en DTO, README/AGENTS/contexto; inició un clúster aislado y corrigió el helper de arranque para separar handles de la terminal. Validación final en Temurin Java 21.0.12.1: **16 tests, 0 fallos, 0 errores, 0 omitidos**, Maven Wrapper test y package correctos. Primero se validó en Java 23 y después en Java 21 sin cambiar el Java del sistema. Flyway V1/V2 y Hibernate validate correctos tanto en miqa_store_test_db como en miqa_store_db.

JAR: target/miqa-store-backend-0.0.1-SNAPSHOT.jar, aproximadamente 59 MB. Se inició con Java 21 en 127.0.0.1:8081 y se comprobaron mediante Invoke-RestMethod seis categorías, cinco productos, filtros combinados, detalle de Vinil (tres materiales/dos extras) y 404 desconocido. Luego se detuvo solo ese proceso, verificando su ruta; **8081 queda libre**. PostgreSQL de .local permanece levantado en 127.0.0.1:55432. Logs/evidencia en .tmp/tests-java21.log, package-java21.log, api.log y product-example.json, sin versionar. El helper se volvió a ejecutar y reconoció la instancia existente sin recrear datos.

## Arquitectura objetivo

Angular permanece en Cloudflare Workers/Static Assets. API Spring Boot en VPS, posible dominio api-store.solucionesmicaela.com (sin configurar). PostgreSQL miqa_store_db en VPS/infraestructura propia, completamente separado del ERP/gigantografias_db. Media también en VPS propio, no Cloudinary/Firebase/S3. No hay integración entre sistemas.

Local: puerto HTTP 8081, bind 127.0.0.1. PostgreSQL 17 instalado se utiliza solo como binario para crear .local/postgres, puerto 55432/127.0.0.1, usuario/contraseña de desarrollo generados y guardados en .local/connection.json, ignorado. No leer/imprimir ese archivo al usuario. Base app miqa_store_db; tests miqa_store_test_db. No se toca el servicio PostgreSQL existente. scripts/Start-LocalPostgres.ps1 es idempotente, Use-LocalDatabase.ps1 carga variables de sesión y Stop-LocalPostgres.ps1 detiene solo el clúster propio.

## Diseño y contrato

Capas controller → servicio readOnly transaccional → repositorios JPA → PostgreSQL; DTOs records, nunca entidades serializadas. Lazy associations y BatchSize para colecciones/categoría, sin Open Session In View. Todos los endpoints son GET públicos:
- /api/public/categories: active y orden displayOrder/id.
- /api/public/products: published y categoría activa; filtros combinables category, search, featured. Búsqueda por nombre case-insensitive, acentos significativos, SQL wildcard escapado; featured admite true/false.
- /api/public/products/{slug}: misma visibilidad, opciones activas e imágenes ordenadas; 404 si no está visible/no existe.

IDs string preservan mocks; DTO incluye categorySlug y category, image/gallery e images con alt, published/featured, saleType/unitLabel/packSize/packLabel/minQuantity/step y arrays materials/extras. Opcionales nullable; Angular aún usa mocks y no fue modificado/conectado. No hay paginación en esta etapa; considerarla antes de catálogos grandes.

Errores JSON consistentes timestamp/status/code/message/path/errors. No stacktrace al cliente. Validación de filtros y slug, 400/404/405 y 500 genérico. CORS solo localhost:4200 con perfil local; fuera de local lista configurable exacta, sin wildcard ni credenciales. No es autenticación.

## Esquema y migraciones

V1__initial_schema.sql: categories/products/materials/extras/images, slugs únicos y no vacíos, enum de venta por check, presentación PACK íntegra, cantidades positivas, órdenes no negativos, FK/índices, máximo una imagen primaria por producto, timestamps con triggers updatedAt. Categoría referenciada usa RESTRICT; hijos de producto CASCADE solo para mantenimiento SQL explícito. Futuro admin debe despublicar/desactivar antes que borrar. Sin precios.

V2__seed_initial_catalog.sql: seis categorías y cinco productos EXACTAMENTE derivados de datos mock frontend (textos/ids/slugs/imagen/materiales/extras). Ya aplicada en tests: no editar estas migraciones, añadir una V3 si hay cambios. ddl-auto=validate; Flyway clean deshabilitado. EnvironmentPostProcessor bloquea hosts remotos y nombres distintos de miqa_store_db/miqa_store_test_db antes de crear DataSource. Al preparar despliegue VPS deberá revisarse expresamente si el host de PostgreSQL es diferente de loopback.

## Media: referencias independientes de Angular

ProductImage.url es string con referencia relativa o URL HTTP(S), no filesystem de frontend. MediaProperties enlaza app.media.storage-path/MEDIA_STORAGE_PATH (default .local/media) y app.media.base-url/MEDIA_BASE_URL (vacío por defecto), con validación de esquema/origen. CatalogService resuelve URLs públicas sin alterar la referencia guardada. storage-path está reservado: no se escriben ni sirven archivos todavía.

Las rutas /images/... de V2 son TEMPORALES para compatibilidad visual; la API no sirve ni copia esos archivos y no depende de Angular para arrancar. Futuro admin: subir → validar → guardar en VPS bajo storage-path → referencia en PostgreSQL → URL con base-url → entrega pública por backend/proxy. El dominio API/media y topología de entrega aún no se deciden; son configurables. No se implementaron upload, /media, autenticación ni almacenamiento externo.

## Validación y límites

CatalogApiTest usa servidor HTTP aleatorio y PostgreSQL real con base fija de tests; comprueba seis categorías, cinco productos, filtros combinables, opciones activas, invisibilidad de borradores/categoría inactiva, JSON/errores, CORS y restricciones/historial Flyway. Fixtures reservados se limpian después de cada test. ConfigurationTest verifica media, protección DB y errores inesperados sanitizados. No H2 ni Testcontainers; tests fallan si falta PostgreSQL/credenciales, no se omiten.

JDK 21 Temurin portable descargado a .tmp/jdk21/jdk-21.0.12.1+1 y checksum SHA-256 verificado para validación; Java del sistema no se cambia. .tmp, .local, target y secretos ignorados. README contiene comandos de arranque/pruebas y alternativa de DB local manual. No frontend, Cloudflare, ERP, producción, upload, admin, autenticación, pedidos, pagos ni WhatsApp Cloud API implementados en esta etapa. No hay cambios que requieran aprobación destructiva; siguiente integración/despliegue requiere nueva tarea. La ejecución de tests emite un aviso de Mockito sobre futura necesidad de javaagent y logs deliberados del caso 500/constraints; Maven finaliza correctamente, no se omiten ni silencian fallos.
