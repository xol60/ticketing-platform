-- V2: price_history — temporal price ledger for point-in-time lookups

CREATE TABLE IF NOT EXISTS price_history (
    id           UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id     VARCHAR(255)   NOT NULL,
    price        NUMERIC(10, 2) NOT NULL,
    valid_from   TIMESTAMPTZ    NOT NULL,
    valid_to     TIMESTAMPTZ,
    triggered_by VARCHAR(50)    NOT NULL DEFAULT 'MANUAL',
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_price_history_event_time
    ON price_history (event_id, valid_from, valid_to);

-- At most one active record per event
CREATE UNIQUE INDEX IF NOT EXISTS idx_price_history_active
    ON price_history (event_id)
    WHERE valid_to IS NULL;
