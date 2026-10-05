-- ERP identities are explicit; existing editorial rows and historical snapshots stay intact.
ALTER TABLE categories ADD COLUMN IF NOT EXISTS erp_category_id varchar(64)
    CHECK (erp_category_id ~ '^[1-9][0-9]*$');
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_erp_identity ON categories(erp_category_id)
    WHERE erp_category_id IS NOT NULL;

ALTER TABLE products ADD COLUMN IF NOT EXISTS catalog_mode varchar(16) NOT NULL DEFAULT 'LEGACY'
    CHECK (catalog_mode IN ('LEGACY', 'ERP'));
-- ERP configuration lives in erp_catalog_services, never in these legacy columns.
ALTER TABLE products ALTER COLUMN sale_type DROP NOT NULL;
ALTER TABLE products ALTER COLUMN unit_label DROP NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'products'::regclass AND conname = 'products_catalog_authority') THEN
        ALTER TABLE products ADD CONSTRAINT products_catalog_authority CHECK (
            (catalog_mode = 'LEGACY' AND sale_type IS NOT NULL AND unit_label IS NOT NULL)
            OR (catalog_mode = 'ERP' AND sale_type IS NULL AND unit_label IS NULL
                AND pack_size IS NULL AND pack_label IS NULL AND min_quantity IS NULL AND quantity_step IS NULL));
    END IF;
END; $$;

ALTER TABLE product_erp_bindings ADD COLUMN IF NOT EXISTS canonical boolean NOT NULL DEFAULT false;
-- Disabled principals still reserve identity. Other historical bindings remain valid.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_erp_canonical ON product_erp_bindings(erp_service_id)
    WHERE canonical;
