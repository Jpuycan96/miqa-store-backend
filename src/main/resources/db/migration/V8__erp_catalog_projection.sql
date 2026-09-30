-- Independent of editorial Product and immutable Phase 1 request snapshots.
CREATE TABLE IF NOT EXISTS erp_catalog_services (
    erp_service_id varchar(64) PRIMARY KEY CHECK (erp_service_id ~ '^[1-9][0-9]*$'),
    catalog_revision varchar(128) NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    sync_state varchar(32) NOT NULL CHECK (sync_state IN ('AVAILABLE', 'PENDING_REVALIDATION')),
    last_synced_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);

-- One current binding per Product, including disabled bindings. Rebinding updates it.
-- The ERP ID is deliberately NOT unique: multiple publications may share a service.
CREATE TABLE IF NOT EXISTS product_erp_bindings (
    product_id varchar(64) PRIMARY KEY REFERENCES products(id) ON DELETE RESTRICT,
    erp_service_id varchar(64) NOT NULL REFERENCES erp_catalog_services(erp_service_id) ON DELETE RESTRICT,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX IF NOT EXISTS idx_product_erp_bindings_service ON product_erp_bindings(erp_service_id);

CREATE TABLE IF NOT EXISTS erp_catalog_sync_status (
    id integer PRIMARY KEY CHECK (id = 1),
    outcome varchar(32) NOT NULL CHECK (outcome IN ('NEVER', 'SUCCESS', 'ERP_ERROR', 'INVALID_CONTRACT', 'NOT_CONFIGURED')),
    attempted_at timestamptz,
    succeeded_at timestamptz,
    received integer NOT NULL DEFAULT 0 CHECK (received >= 0),
    changed integer NOT NULL DEFAULT 0 CHECK (changed >= 0),
    missing integer NOT NULL DEFAULT 0 CHECK (missing >= 0)
);
INSERT INTO erp_catalog_sync_status(id, outcome) VALUES (1, 'NEVER') ON CONFLICT (id) DO NOTHING;
