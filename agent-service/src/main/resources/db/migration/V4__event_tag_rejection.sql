-- A review needs to be able to say no, and have the no survive.
--
-- event_tag could record approval and nothing else, so a reviewer rejecting a
-- suggestion had only one move: delete the row. Ingestion clears an event's
-- llm rows before writing fresh ones, and the matcher is deterministic — the
-- same facet produces the same nearest tag every time — so a deleted rejection
-- reappeared on the next re-ingest, identical, unreviewed. The reviewer's work
-- was discarded on a schedule.
--
-- With this column a rejection is a stored decision the pipeline reads, and
-- the review converges instead of resetting.
ALTER TABLE event_tag ADD COLUMN rejected_at TIMESTAMPTZ;

ALTER TABLE event_tag ADD CONSTRAINT event_tag_one_verdict
    CHECK (approved_at IS NULL OR rejected_at IS NULL);

-- Read on every re-ingest to decide which pairs must not be suggested again.
CREATE INDEX idx_event_tag_rejected ON event_tag(event_id) WHERE rejected_at IS NOT NULL;
