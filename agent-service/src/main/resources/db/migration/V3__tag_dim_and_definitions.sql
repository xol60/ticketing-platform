-- ============================================================================
--  Tags become a retrievable vocabulary, not a fixed list in a prompt
-- ============================================================================
--  Until now tags did almost nothing. The only query that read event_tag was a
--  NOT EXISTS for exclusions, so the ten category tags — live-music, sports and
--  the rest — were written on every ingest and never read: nobody asks for
--  "not live music". They also duplicated agent_event.category, which comes
--  from ticket-service and is more trustworthy than anything inferred.
--
--  Three changes turn them into the primary retrieval path.
-- ============================================================================

-- ── dim ─────────────────────────────────────────────────────────────────────
-- A tag now answers one of the same eight questions a facet does, so a facet
-- is only ever compared against tags on its own dim.
--
-- Without this, everything competes with everything. Measured on the running
-- model, two phrases on *different* dims still score 0.568 — higher than two
-- unrelated phrases on the same dim (0.452). Cross-dim comparison flattens the
-- whole space, and "a small room, close to the performer" was matching
-- live-music instead of intimate for exactly that reason.
--
-- Nullable on purpose. headliner and late-night describe an artist's fame and a
-- start time; neither is a dimension of the experience, and forcing them into
-- one would put them in competition with facets they have nothing to do with.
-- A null dim means "exclusion only" — reachable by NOT EXISTS, never matched.
ALTER TABLE tag ADD COLUMN dim TEXT;

-- ── examples ────────────────────────────────────────────────────────────────
-- Embedded together with name and description. The slug alone is far too short
-- to carry meaning: embedding "intimate" scored 0.556 against "a small room,
-- close to the performer, only a hundred people" and lost to live-music;
-- embedding the definition scored 0.819 and won. Examples widen that further,
-- and cost nothing at query time because the vector is computed once.
ALTER TABLE tag ADD COLUMN examples TEXT;

-- ── source ──────────────────────────────────────────────────────────────────
-- TagSynchronizer rewrites every tag from the Java constant on each startup.
-- A tag added by a reviewer has no Java counterpart, so without this column the
-- next restart would silently revert it — the vocabulary could only ever shrink
-- back to the fifteen it began with.
ALTER TABLE tag ADD COLUMN source TEXT NOT NULL DEFAULT 'taxonomy'
    CHECK (source IN ('taxonomy', 'human'));

CREATE INDEX idx_tag_dim ON tag(dim) WHERE dim IS NOT NULL;

-- ── Retire low-cost ─────────────────────────────────────────────────────────
-- Price is already a hard filter on agent_event.price_min, which is exact and
-- comes from the ticket table. A tag restating it is a fuzzier copy of a fact
-- SQL already decides perfectly. Safe to delete: it was never assigned.
DELETE FROM event_tag WHERE tag_id IN (SELECT id FROM tag WHERE slug = 'low-cost');
DELETE FROM tag WHERE slug = 'low-cost';

-- ── Force a re-embed ────────────────────────────────────────────────────────
-- Existing vectors, where any exist, were built from the slug. They are the
-- wrong thing to compare against and must not survive.
UPDATE tag SET embedding = NULL, vector_source = NULL;
