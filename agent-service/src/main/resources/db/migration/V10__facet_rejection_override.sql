-- Lets a reviewer overturn one gate, and only one.
--
-- facet_rejection was write-only: the gates recorded what they blocked and
-- nothing ever read it back. That is fine as telemetry and wrong as a queue,
-- because the overlap gate is a heuristic with a threshold and heuristics are
-- wrong in both directions. Two failures found by reading the queue:
--
--   "traditional and ceremonial"  <- "Wimbledon's traditions are as iconic..."
--        scored 0.00 because the stemmer does not relate traditions/traditional
--   "two days"                    <- "The 2026 edition (May 19-20)"
--        a correct inference the overlap check cannot make at all
--
-- The restriction is the point. Only LOW_SPAN_OVERLAP may be overridden: there
-- the span is real and the argument is whether the facet fairly restates it,
-- which is a judgement a person can make. SPAN_NOT_IN_SOURCE means the quote
-- does not exist in the description, and overriding that is not a judgement
-- call — it is accepting a fabrication, which is the one thing the whole
-- design refuses. The constraint says so rather than trusting a UI to.
ALTER TABLE facet_rejection ADD COLUMN overridden_at TIMESTAMPTZ;

ALTER TABLE facet_rejection ADD CONSTRAINT ck_override_only_overlap
    CHECK (overridden_at IS NULL OR reason = 'LOW_SPAN_OVERLAP');

-- Read by the promotion step to find what a reviewer reinstated.
CREATE INDEX idx_facet_rejection_overridden
    ON facet_rejection(overridden_at) WHERE overridden_at IS NOT NULL;
