-- ============================================================================
--  outbox_events — hybrid outbox for Kafka publish failures
-- ============================================================================
--  Pattern: try Kafka publish synchronously (sync wait for ack); on failure
--  persist the event row here. A scheduled OutboxDrainWorker retries until
--  the row is either successfully published or hits a max-retry ceiling.
--
--  Deliberately NOT written inside the business transaction — only on failure.
--  Trade-off: narrow window of event loss if the process crashes between tx
--  commit and Kafka publish attempt. Accepted in exchange for happy-path
--  latency (no extra DB write in 99%+ of publishes).
-- ============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id            UUID PRIMARY KEY,
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- Partial index — only unpublished rows. Healthy state: ~0 rows. Drain worker
-- query is essentially free when there's nothing to publish.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;
