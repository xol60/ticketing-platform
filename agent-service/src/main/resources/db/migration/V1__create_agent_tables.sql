-- ============================================================================
--  Agent read model — taxonomy, materialised event projection, facets
-- ============================================================================
--  This database is a derived read model, not a source of truth. Every row in
--  agent_event arrives from the event.search.indexed Kafka topic, whose
--  producer is ticket-service. Dropping this whole database loses no business
--  data: replay the topic from its 168 h retention and it rebuilds, minus the
--  human review decisions (which is why approved_at is worth backing up and
--  the rest is not).
--
--  Vector dimension is 1024 throughout — the native width of voyage-3, and
--  also of bge-m3 and e5-large, so the provider can change without a schema
--  migration. Changing the *dimension* is a reindex, not an ALTER: see
--  model_version below.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ── Cities ──────────────────────────────────────────────────────────────────
-- ticket-service stores venue_city as free text ('Hà Nội', 'TP. Hồ Chí Minh',
-- 'New York'). A city is a hard filter and must never reach the vector — the
-- string 'new york' inside an embedding will happily match a Boston event whose
-- description says "unlike the crowded New York scene". So city is resolved to
-- an integer at ingest and filtered with =.
--
-- Rows are created on first sight rather than seeded, so this table can never
-- drift from the corpus. Aliases carry the folded forms a person actually
-- types: lowercase, unaccented, punctuation stripped ('ha noi', 'hanoi').
CREATE TABLE city (
    id             SERIAL PRIMARY KEY,
    canonical_name TEXT NOT NULL UNIQUE
);

CREATE TABLE city_alias (
    alias   TEXT PRIMARY KEY,
    city_id INT  NOT NULL REFERENCES city(id) ON DELETE CASCADE
);

-- ── Tags ────────────────────────────────────────────────────────────────────
-- Closed set of 15, mirrored from com.ticketing.common.agent.Taxonomy on every
-- startup. Java is the source of truth for slug/name/description/kind; this
-- table exists so tags can be joined and filtered in SQL and so each one can
-- carry a vector.
--
-- vector_source records how the embedding was produced:
--   'description' — bootstrap, embedded from the tag's own description text.
--                   The only option on day zero, before any event is approved.
--   'knn'         — centroid of approved events carrying the tag. Switched over
--                   once a tag reaches roughly 20 approved events, at which
--                   point real usage describes the tag better than prose does.
CREATE TABLE tag (
    id            SERIAL PRIMARY KEY,
    slug          TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    description   TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('CATEGORY', 'ATTRIBUTE')),
    embedding     vector(1024),
    vector_source TEXT CHECK (vector_source IN ('description', 'knn')),
    model_version TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Event projection ────────────────────────────────────────────────────────
-- The filterable snapshot of an event. Only the columns the hard filter and
-- the compare projection actually read — this is deliberately not a mirror of
-- ticket_db.events.
--
-- searchable vs status: these answer different questions and must not be
-- merged. status is the business lifecycle (is it on sale?) and comes from
-- ticket-service. searchable is a curation gate (has a human accepted the
-- facets we generated for it?) and is owned here. An event can be perfectly
-- OPEN for sales and still be invisible to the agent because its facets are
-- unreviewed, and that is the correct behaviour — surfacing an event on the
-- strength of hallucinated facets is worse than not surfacing it.
CREATE TABLE agent_event (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    primary_artist  TEXT,
    venue_name      TEXT,
    venue_city      TEXT,
    city_id         INT REFERENCES city(id),
    category        TEXT,
    genre           TEXT,
    status          TEXT NOT NULL,
    start_at        TIMESTAMPTZ NOT NULL,
    sales_open_at   TIMESTAMPTZ,
    sales_close_at  TIMESTAMPTZ,

    -- From ticket-service's MIN/MAX over the event's tickets. Nullable because
    -- an event can exist before any ticket is created for it.
    price_min       NUMERIC(10,2),
    price_max       NUMERIC(10,2),

    -- Derived from ticket count at ingest, not from adjectives in the copy.
    capacity_band   TEXT CHECK (capacity_band IN ('small', 'medium', 'large')),

    -- The text the facets were distilled from, kept so a reviewer can answer
    -- "where in the source does this facet come from?" without a second service
    -- call. Not indexed and never embedded whole.
    description_raw TEXT,

    searchable      BOOLEAN NOT NULL DEFAULT FALSE,
    ingested_at     TIMESTAMPTZ,
    model_version   TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The hard filter's covering path: city + date, restricted to reviewed events.
-- The partial predicate keeps unreviewed and past events out of the index
-- entirely rather than filtering them out after the scan.
CREATE INDEX idx_agent_event_city_start
    ON agent_event (city_id, start_at)
    WHERE searchable;

-- Review queue: what a human still has to look at.
CREATE INDEX idx_agent_event_pending
    ON agent_event (ingested_at)
    WHERE NOT searchable;

CREATE INDEX idx_agent_event_status ON agent_event (status);

-- ── Event ↔ tag ─────────────────────────────────────────────────────────────
-- source distinguishes a machine guess from a human decision, so a reviewer's
-- correction is never silently overwritten by the next ingest of the same
-- event. approved_at NULL means proposed but not yet accepted; the exclude
-- filter only ever considers approved rows, because excluding on an unapproved
-- tag would hide events for a reason nobody has checked.
CREATE TABLE event_tag (
    event_id    TEXT NOT NULL REFERENCES agent_event(id) ON DELETE CASCADE,
    tag_id      INT  NOT NULL REFERENCES tag(id),
    source      TEXT NOT NULL CHECK (source IN ('llm', 'human')),
    confidence  REAL,
    approved_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, tag_id)
);

CREATE INDEX idx_event_tag_tag ON event_tag (tag_id) WHERE approved_at IS NOT NULL;

-- ── Event facets ────────────────────────────────────────────────────────────
-- Deliberately NO unique constraint on (event_id, dim). An event may carry
-- several facets on one dim, or none at all. The dim vocabulary is closed; the
-- count is not. A schema that forced one row per dim would push the extractor
-- into inventing values to fill the shape, and an invented facet is
-- indistinguishable from a real one once embedded.
--
-- embedding is NULL for the five dims that are not embedded (duration,
-- audience, scale, participation, setting). Those are kept as text for
-- rendering result rows and for the compare projection, where the differences
-- between two events are read off structured fields rather than prose.
CREATE TABLE event_facet (
    id            BIGSERIAL PRIMARY KEY,
    event_id      TEXT NOT NULL REFERENCES agent_event(id) ON DELETE CASCADE,
    dim           TEXT NOT NULL,
    value         TEXT NOT NULL,
    embedding     vector(1024),
    model_version TEXT,
    source        TEXT NOT NULL DEFAULT 'llm' CHECK (source IN ('llm', 'human')),
    approved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_event_facet_event ON event_facet (event_id);

-- Facet matching scans by dim, then compares vectors inside that dim only.
-- Mixing dims into one comparison is what produces a flat cosine ~0.3 across
-- an entire dataset.
CREATE INDEX idx_event_facet_dim ON event_facet (dim) WHERE embedding IS NOT NULL;

-- No HNSW index yet, on purpose. After the city + date hard filter the
-- candidate set is well under a thousand rows, where brute-force cosine is
-- sub-millisecond. Adding HNSW now would invite the classic pre-filtered-ANN
-- failure: the graph traversal cannot find enough surviving rows under a tight
-- filter, so it degrades to post-filtering and gets slower, not faster.
-- Revisit only if p99 says so, and only for the loose-filter case.

-- ── Tag proposals ───────────────────────────────────────────────────────────
-- When the extractor emits a label that snaps to no existing tag, it lands
-- here instead of creating a tag. The tag set grows at human speed, not at
-- ingest speed — otherwise a single odd event permanently widens the
-- vocabulary and every filter built on it gets fuzzier.
--
-- Promotion is a periodic manual review: a label with a high seen_count whose
-- nearest_score is comfortably below the snap threshold is a genuine gap.
CREATE TABLE tag_proposal (
    id            BIGSERIAL PRIMARY KEY,
    raw_label     TEXT NOT NULL UNIQUE,
    seen_count    INT  NOT NULL DEFAULT 1,
    last_event_id TEXT,
    nearest_slug  TEXT,
    nearest_score REAL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tag_proposal_seen ON tag_proposal (seen_count DESC);
