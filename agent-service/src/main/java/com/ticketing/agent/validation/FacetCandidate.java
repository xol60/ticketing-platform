package com.ticketing.agent.validation;

/**
 * One facet as the extractor emitted it, before any gate has run.
 *
 * @param dim   claimed dimension — validated against the closed vocabulary
 * @param value the distilled statement
 * @param span  the substring of the source description the model claims this
 *              was derived from. Required for machine output: it is the only
 *              thing separating a facet that was read from one that was
 *              invented, and no later gate can recover that distinction.
 */
public record FacetCandidate(String dim, String value, String span) {

    public FacetCandidate {
        dim   = dim   == null ? null : dim.trim();
        value = value == null ? null : value.trim();
        span  = span  == null ? null : span.trim();
    }
}
