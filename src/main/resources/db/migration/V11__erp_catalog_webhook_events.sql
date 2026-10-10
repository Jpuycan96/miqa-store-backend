-- Technical notifications only; never payloads, secrets or commercial/editorial data.
CREATE TABLE erp_catalog_webhook_events (
    event_id uuid PRIMARY KEY,
    event_type varchar(32) NOT NULL CHECK (event_type = 'ERP_CATALOG_CHANGED'),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    occurred_at timestamptz NOT NULL,
    body_sha256 varchar(64) NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    received_at timestamptz NOT NULL DEFAULT current_timestamp,
    state varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','PROCESSING','RETRY','PROCESSED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz,
    processing_token uuid,
    processing_started_at timestamptz,
    processed_at timestamptz,
    last_outcome varchar(32) CHECK (last_outcome IN
        ('SUCCESS','ERP_ERROR','NOT_CONFIGURED','INVALID_CONTRACT','SYNC_BUSY','WORKER_ERROR','WORKER_RECOVERED')),
    CHECK ((state IN ('PENDING','RETRY')) = (next_attempt_at IS NOT NULL)),
    CHECK ((state = 'PROCESSING') = (processing_token IS NOT NULL)),
    CHECK ((state = 'PROCESSING') = (processing_started_at IS NOT NULL)),
    CHECK ((state = 'PROCESSED') = (processed_at IS NOT NULL))
);
CREATE INDEX idx_erp_webhook_due ON erp_catalog_webhook_events(next_attempt_at,received_at,event_id)
    WHERE state IN ('PENDING','RETRY');
CREATE INDEX idx_erp_webhook_processing ON erp_catalog_webhook_events(processing_token)
    WHERE state='PROCESSING';
CREATE INDEX idx_erp_webhook_pending_order ON erp_catalog_webhook_events(received_at,event_id)
    WHERE state IN ('PENDING','RETRY');
