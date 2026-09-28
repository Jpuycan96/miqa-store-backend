CREATE SEQUENCE quote_request_reference_seq AS bigint START WITH 1 NO CYCLE;

CREATE TABLE quote_requests (
    id varchar(64) PRIMARY KEY,
    reference_number bigint NOT NULL UNIQUE CHECK (reference_number > 0),
    reference varchar(32) NOT NULL UNIQUE,
    status varchar(24) NOT NULL DEFAULT 'RECIBIDA' CHECK (status = 'RECIBIDA'),
    origin varchar(24) NOT NULL DEFAULT 'TIENDA_VIRTUAL' CHECK (origin = 'TIENDA_VIRTUAL'),
    contact_name varchar(160) NOT NULL CHECK (btrim(contact_name) <> ''),
    contact_phone varchar(16) NOT NULL CHECK (contact_phone ~ '^\+?[0-9]{7,15}$'),
    contact_email varchar(254),
    notes varchar(1000),
    idempotency_key uuid NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT uq_quote_requests_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT quote_request_reference_format CHECK (
        reference = 'MIQA-' || CASE WHEN reference_number < 1000000
            THEN lpad(reference_number::text, 6, '0') ELSE reference_number::text END
    )
);

CREATE TABLE quote_request_items (
    id varchar(64) PRIMARY KEY,
    request_id varchar(64) NOT NULL REFERENCES quote_requests(id) ON DELETE RESTRICT,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 50),
    -- Historical values: deliberately no FK to the mutable catalog.
    product_id varchar(64) NOT NULL,
    product_name varchar(200) NOT NULL,
    product_slug varchar(160) NOT NULL,
    sale_type varchar(16) NOT NULL CHECK (sale_type IN ('QUANTITY', 'PACK', 'AREA')),
    quantity bigint NOT NULL CHECK (quantity BETWEEN 1 AND 1000000000),
    unit_label varchar(40) NOT NULL,
    pack_size integer,
    pack_label varchar(40),
    width_meters numeric(10,6),
    height_meters numeric(10,6),
    area_square_meters numeric(19,12),
    notes varchar(1000),
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (request_id, position),
    CONSTRAINT quote_item_pack CHECK (
        (sale_type = 'PACK' AND pack_size IS NOT NULL AND pack_size > 0
            AND pack_label IS NOT NULL AND btrim(pack_label) <> '')
        OR (sale_type <> 'PACK' AND pack_size IS NULL AND pack_label IS NULL)
    ),
    CONSTRAINT quote_item_area CHECK (
        (sale_type = 'AREA' AND width_meters IS NOT NULL AND height_meters IS NOT NULL
            AND area_square_meters IS NOT NULL
            AND width_meters BETWEEN 0.01 AND 1000 AND height_meters BETWEEN 0.01 AND 1000
            AND area_square_meters = width_meters * height_meters)
        OR (sale_type <> 'AREA' AND width_meters IS NULL AND height_meters IS NULL AND area_square_meters IS NULL)
    ),
    CONSTRAINT quote_item_snapshot CHECK (
        jsonb_typeof(snapshot) = 'object' AND snapshot @> '{"schemaVersion": 1}'::jsonb
    )
);

CREATE INDEX idx_quote_requests_received ON quote_requests(created_at DESC, id);
-- UNIQUE(request_id, position) already indexes the FK and ordered item lookup.
CREATE TRIGGER quote_requests_updated BEFORE UPDATE ON quote_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER quote_request_items_updated BEFORE UPDATE ON quote_request_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
