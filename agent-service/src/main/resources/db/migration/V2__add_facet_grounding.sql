-- ============================================================================
--  Facet grounding — evidence, and a record of what was thrown away
-- ============================================================================
--  V1 assumed a frontier model whose facets could be trusted to describe the
--  source. Inference now runs locally on an 8B model, which fabricates readily
--  and — the part that matters — fabricates fluently: an invented facet reads
--  exactly like a real one, sits in the right dim, and snaps to a sensible
--  tag. Nothing in V1 could tell the two apart, because nothing in V1 compared
--  a facet back against the text it supposedly came from.
--
--  So every facet now has to cite its evidence, and everything rejected is
--  kept rather than dropped.
-- ============================================================================

-- ── Evidence ────────────────────────────────────────────────────────────────
-- The exact substring of agent_event.description_raw the extractor claims this
-- facet came from. Verified in Java by literal containment (after whitespace
-- and case normalisation) before the row is ever written — no threshold, no
-- model, no vector.
--
-- That check is the strongest gate in the pipeline precisely because it is
-- dumb. Inventing a plausible facet is easy for a small model; inventing a
-- character sequence that happens to appear verbatim in a specific paragraph
-- is not.
--
-- Nullable for two legitimate cases: rows written by a human reviewer, who is
-- the evidence, and facets derived from structured columns rather than prose.
ALTER TABLE event_facet ADD COLUMN source_span TEXT;

-- Human-authored facets need no span; machine ones must have one.
ALTER TABLE event_facet ADD CONSTRAINT ck_event_facet_span_required
    CHECK (source = 'human' OR source_span IS NOT NULL);

-- ── Rejections ──────────────────────────────────────────────────────────────
-- Facets that failed a gate. Deliberately a separate table rather than a
-- status column on event_facet: rejected rows must be impossible to select by
-- accident. A forgotten `WHERE rejected = false` in one query is all it would
-- take to put fabricated facets back into the vector space.
--
-- Kept rather than discarded because this table is the only honest measure of
-- how much the extractor is making up. A rejection rate climbing after a
-- prompt edit is the signal; without the rows, the failure is invisible and
-- looks like the corpus simply having fewer facets.
CREATE TABLE facet_rejection (
    id            BIGSERIAL PRIMARY KEY,
    event_id      TEXT NOT NULL REFERENCES agent_event(id) ON DELETE CASCADE,

    -- What the model produced, stored exactly as emitted so a prompt change
    -- can be evaluated against real past failures.
    dim           TEXT,
    value         TEXT,
    source_span   TEXT,

    -- Which gate rejected it. Values mirror the RejectionReason enum:
    --   SPAN_NOT_IN_SOURCE   cited text does not occur in description_raw
    --   SPAN_TOO_SHORT       span too generic to be evidence
    --   LOW_SPAN_OVERLAP     span is real, but the facet is about something else
    --   CONTRADICTS_EVENT    conflicts with a fact already known from the DB
    --   UNKNOWN_DIM          dim outside the closed vocabulary
    --   EMPTY_VALUE          blank after trimming
    reason        TEXT NOT NULL,

    -- Human-readable specifics, e.g. which column it contradicted.
    detail        TEXT,

    model_version TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The two questions this table is asked: "how is the extractor failing?" and
-- "why does this event have so few facets?"
CREATE INDEX idx_facet_rejection_reason ON facet_rejection (reason, created_at DESC);
CREATE INDEX idx_facet_rejection_event  ON facet_rejection (event_id);

-- ── Ingest bookkeeping ──────────────────────────────────────────────────────
-- Counts from the most recent extraction, so the review UI can show "4 kept,
-- 7 rejected" without aggregating the rejection table per row, and so a
-- pathological event is visible at a glance.
ALTER TABLE agent_event ADD COLUMN facets_kept     INT NOT NULL DEFAULT 0;
ALTER TABLE agent_event ADD COLUMN facets_rejected INT NOT NULL DEFAULT 0;

-- Events where the extractor produced almost nothing usable. This is the
-- §15.1 measurement made queryable: if this set is large, the problem is
-- upstream in how events are described, and no prompt or ranking work will
-- fix it.
CREATE INDEX idx_agent_event_starved
    ON agent_event (facets_kept)
    WHERE facets_kept < 2;
