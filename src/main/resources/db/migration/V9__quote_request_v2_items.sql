-- New requests may contain legacy v1 snapshots and ERP v2 snapshots in their original order.
-- Existing quote_request_items and their v1 checks remain unchanged.
CREATE TABLE IF NOT EXISTS quote_request_v2_items (
    id varchar(64) PRIMARY KEY,
    request_id varchar(64) NOT NULL REFERENCES quote_requests(id) ON DELETE RESTRICT,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 50),
    product_id varchar(64) NOT NULL,
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE(request_id, position),
    CHECK (jsonb_typeof(snapshot) = 'object' AND snapshot ? 'schemaVersion'
        AND snapshot->>'schemaVersion' IN ('1', '2'))
);
