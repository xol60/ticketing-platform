-- Records every tag a facet was compared against, not just the winner.
--
-- Matching stored one row per facet: the single nearest tag. That is enough to
-- ask "is this right?" and not enough to ask "which is right?" or "is any right
-- at all?" — the two questions a review actually has to answer. The cost was
-- measurable. Across the facets rejected in the first review:
--
--   free practice sessions      workshop 0.495   sports 0.495   <- exact tie
--   qualifying session          sports   0.502   workshop 0.501
--   showcase of the league's…   comedy   0.509   sports   0.508
--
-- A Formula 1 race was tagged 'workshop' on a tie broken by row order. The
-- correct tag was sitting at rank two in every case, discarded before anyone
-- could see it.
--
-- The opposite case matters just as much. For "worldwide acclaim" the nearest
-- tags were large-scale 0.335 and intimate 0.327 — neither fits, and the right
-- answer is a new tag. With only a winner stored, "none of these" is not
-- something the data can express; a reviewer has to supply it from outside.
CREATE TABLE facet_tag_candidate (
    facet_id BIGINT   NOT NULL REFERENCES event_facet(id) ON DELETE CASCADE,
    tag_id   INTEGER  NOT NULL REFERENCES tag(id),
    score    REAL     NOT NULL,
    rank     SMALLINT NOT NULL CHECK (rank >= 1),
    PRIMARY KEY (facet_id, tag_id)
);

-- The review reads a facet's candidates in order; nothing looks them up by tag.
CREATE INDEX idx_ftc_facet_rank ON facet_tag_candidate(facet_id, rank);

-- Candidates belong to the facet that produced them and are regenerated
-- whenever it is. Verdicts stay on event_tag, keyed by (event, tag), because
-- re-ingesting an event deletes and recreates its facets — a verdict stored
-- against a facet id would not survive the next ingest, which is exactly the
-- failure V4 was written to prevent.

-- 'professional' was added to Taxonomy.java during review. That was the wrong
-- place: it made widening the vocabulary a code change and a redeploy, when the
-- design is for a reviewer to add a tag and have it take effect at once. The
-- row stays, the definition moves to where the vocabulary lives.
UPDATE tag SET source = 'human' WHERE slug = 'professional';
