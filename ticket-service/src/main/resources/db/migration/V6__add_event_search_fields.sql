-- ============================================================================
--  V6 — Add searchable metadata columns to events
-- ============================================================================
--  Powers the Elasticsearch event-search subsystem (search-service module).
--  All columns are nullable so existing rows are not invalidated.
--  Postgres stays the source of truth; ES is a derived read-index.
-- ============================================================================

ALTER TABLE events
    ADD COLUMN IF NOT EXISTS primary_artist     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS venue_name         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS venue_city         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS short_description  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS full_description   TEXT,
    ADD COLUMN IF NOT EXISTS category           VARCHAR(50),
    ADD COLUMN IF NOT EXISTS genre              VARCHAR(50);

-- Light indexes for admin "list / filter by category/genre" queries —
-- nothing here is meant to power full-text search; that's ES's job.
CREATE INDEX IF NOT EXISTS idx_events_category ON events (category);
CREATE INDEX IF NOT EXISTS idx_events_genre    ON events (genre);
