# PostgreSQL TEST: categorías editoriales ERP

Preparada, no ejecutada contra PostgreSQL. No necesita dependencias adicionales.

## Ejecución autorizada posterior

Desde el backend, con PostgreSQL TEST previamente iniciado y la contraseña correcta
en `TEST_DB_PASSWORD` (no imprimirla ni guardarla en este documento):

```powershell
$env:RUN_ERP_CATEGORY_EDITORIAL_IT = 'true'
try {
    .\mvnw.cmd -o '-Dtest=ErpCategoryEditorialPersistenceIT' test
    if ($LASTEXITCODE -ne 0) { throw 'Prueba de persistencia TEST fallida' }
} finally {
    Remove-Item Env:RUN_ERP_CATEGORY_EDITORIAL_IT -ErrorAction SilentlyContinue
}
```

Sin el flag la prueba queda omitida. No ejecutar una suite amplia para esta validación.
El comando no inicia PostgreSQL. La contraseña debe prepararla el propietario;
no reutilizar los scripts históricos que rotan credenciales.

## Aislamiento y alcance

Contexto explícito AnnotationConfigApplicationContext: sin SpringApplication,
autoconfiguración, escaneo de componentes, HTTP, Flyway, inicializadores SQL,
bootstrap, scheduling ni cliente remoto ERP. Flyway está explícitamente deshabilitado
y ni siquiera se registra su bean. Hibernate usa validate y generación JPA none;
no create/update. DataSource literal, independiente de DB_*/SPRING_* y perfiles.

Antes de crear JPA o escribir: conexión read-only para comprobar base, usuario y
session_user, dirección loopback, puerto 55432 y data_directory exacto
`D:/MIQA-STORE/miqa-store-backend/.local/postgres-test/data`.
Verifica columnas utilizadas, historial exitoso V5/V8/V10 e índices únicos ERP
válidos/listos. Hibernate valida las entidades del catálogo. Esquema public fijado
por conexión. Cualquier discrepancia aborta: no prepara ni migra el esquema.

Fixtures aleatorios: UUID de categoría, slug category-editorial-it-UUID,
identidades ERP numéricas aleatorias, proyección sintética y producto/binding
creados por reconciliación local. No se importan datos del ERP ni Legacy.
La configuración técnica sintética es null: esta prueba verifica persistencia
editorial/reconciliación, no elegibilidad pública ni precios.

El servicio real guarda con transacción JPA y flush; consultas JDBC posteriores
fuera de esa transacción verifican el commit desde otra conexión. Reconciliación
JDBC en transacciones explícitas del mismo JpaTransactionManager.
Comprueba rechazo del cambio de nombre y propiedades JSON protegidas, invariancia
editorial e identidades tras dos reconciliaciones y un único producto/categoría.

## Limpieza y recuperación

AfterEach se ejecuta también ante aserciones fallidas. Elimina en una transacción
solo bindings/productos asociados a la combinación exacta servicio y categoría
sintéticos, después proyección y categoría (id + ERP ID + slug). Comprueba ausencia
desde consultas posteriores. Nunca TRUNCATE ni DELETE por prefijos. Cierra el
contexto en finally. No ejecutar simultáneamente con trabajos sobre ese fixture.

Una terminación del proceso puede impedir AfterEach. El reporte/salida muestra,
ANTES de insertar, `ERP_CATEGORY_EDITORIAL_IT fixtures: category=... erpCategory=...
erpService=...`; conservar esa línea (sin contraseña). Para recuperar:

1. Con autorización, repetir las comprobaciones de aislamiento anteriores.
2. Consultar por los tres identificadores exactos de esa ejecución, confirmar el
   slug y los nombres sintéticos y revisar bindings y productos relacionados.
3. En BEGIN, eliminar los bindings del servicio cuyos productos pertenezcan a esa
   categoría; eliminar esos IDs de productos exactos; eliminar la proyección por
   servicio y categoría por UUID + ERP ID + slug. Revisar las filas afectadas antes
   de COMMIT; ante diferencias, ROLLBACK y revisión manual.
4. Confirmar ausencia de categoría, productos, bindings y proyección del fixture.

No limpiar filas por parecido de nombre, rangos ERP ni una categoría real.
Si no se dispone de los identificadores de la salida, detenerse: no adivinarlos.
Los commits son exclusivamente TEST; no existe rollback global que pruebe a la vez
persistencia entre conexiones. La ejecución PostgreSQL sigue pendiente de autorización.
