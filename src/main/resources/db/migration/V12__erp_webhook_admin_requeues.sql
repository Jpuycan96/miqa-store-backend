-- Durable administrative recovery audit. V11 and webhook receipts remain unchanged.
CREATE TABLE erp_catalog_webhook_requeues (
    request_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES erp_catalog_webhook_events(event_id) ON DELETE RESTRICT,
    admin_id varchar(64) NOT NULL CHECK (length(btrim(admin_id)) > 0),
    reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 8 AND 500),
    previous_attempts integer NOT NULL CHECK (previous_attempts >= 0),
    previous_outcome varchar(32) NOT NULL CHECK (previous_outcome IN ('NOT_CONFIGURED','INVALID_CONTRACT')),
    requeued_at timestamptz NOT NULL DEFAULT current_timestamp,
    next_attempt_at timestamptz NOT NULL
);
-- No FK to admin_users: retain actor identity if the account is later removed.
CREATE INDEX idx_erp_webhook_requeues_event ON erp_catalog_webhook_requeues(event_id, requeued_at);
CREATE INDEX idx_erp_webhook_failed_order ON erp_catalog_webhook_events(received_at, event_id) WHERE state='FAILED';
