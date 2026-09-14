# Instrucciones del backend MIQA Store

Antes de modificar el proyecto, leer PROJECT_CONTEXT.md completo y README.md. Inspeccionar archivos y estado Git real; preservar trabajo pendiente. No asumir que frontend/ERP comparten infraestructura o base de datos.

- Trabajar en este backend salvo autorización expresa para otro proyecto.
- Java 21, Maven Wrapper, Spring Boot, DTOs; no exponer entidades JPA.
- Flyway administra el esquema. No usar Hibernate create/update ni editar migraciones ya aplicadas.
- Mantener separadas miqa_store_db y miqa_store_test_db del ERP/gigantografias_db.
- Tests de integración solo en miqa_store_test_db local; no emplear datos de desarrollo o producción como fixtures.
- No añadir autenticación/admin/upload/pagos/ERP sin alcance solicitado.
- Media futura en VPS propio mediante referencias y configuración; no acoplar a Angular ni introducir proveedores externos de almacenamiento.
- No leer/publicar credenciales de .local, .env ni logs completos con posibles secretos. .local y .tmp son locales y están ignorados.
- Validar tests, package y Flyway después de cambios relevantes, y actualizar PROJECT_CONTEXT.md con resultados comprobados y limitaciones.
- No inicializar Git, hacer commit, push o deploy sin autorización explícita.
