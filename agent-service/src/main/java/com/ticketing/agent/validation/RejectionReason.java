package com.ticketing.agent.validation;

/**
 * Why a candidate facet did not make it into the vector space.
 *
 * <p>Stored verbatim in {@code facet_rejection.reason}, so these names are
 * part of the schema — renaming one orphans the history it was meant to
 * explain.
 *
 * <p>The distribution across these values is the most useful diagnostic the
 * service produces. Each points somewhere different:
 * {@link #SPAN_NOT_IN_SOURCE} climbing means the model is inventing rather
 * than reading; {@link #LOW_SPAN_OVERLAP} climbing means it is reading but
 * drifting; {@link #CONTRADICTS_EVENT} climbing means it is asserting things
 * the database already disproves.
 */
public enum RejectionReason {

    /**
     * The cited span does not occur in the source description.
     *
     * <p>The clearest signal of outright fabrication, and the reason the span
     * is demanded at all. A small model will happily invent a facet; inventing
     * a character sequence that happens to appear verbatim in one specific
     * paragraph is a different order of accident.
     */
    SPAN_NOT_IN_SOURCE,

    /**
     * The span is real but too short to establish anything.
     *
     * <p>Without a floor, "the" is a valid citation for any claim. Short spans
     * are how a grounding check gets satisfied without being met.
     */
    SPAN_TOO_SHORT,

    /**
     * The span occurs in the source, but the facet is about something else.
     *
     * <p>The second-order failure: quote a real sentence, then write an
     * unrelated claim beside it. Passes grounding, fails on content.
     */
    LOW_SPAN_OVERLAP,

    /**
     * The facet asserts something the event record already contradicts —
     * an intimate room for an event with twenty thousand tickets.
     *
     * <p>Decidable without the model or the source text, because the
     * contradicted fact is derived rather than described.
     */
    CONTRADICTS_EVENT,

    /** Dim outside the closed vocabulary. Dropped rather than coerced. */
    UNKNOWN_DIM,

    /** Nothing left after trimming. */
    EMPTY_VALUE
}
