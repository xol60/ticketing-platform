package com.ticketing.agent.llm;

import com.ticketing.agent.validation.FacetCandidate;

import java.util.List;

/**
 * Raw output of one ingestion call, before any gate has run.
 *
 * <p>"Candidate" throughout, deliberately. Nothing here has been checked
 * against the source yet, and on a local 8B model a meaningful share of it
 * will turn out to be invented — naming these "facets" would invite treating
 * them as findings.
 *
 * @param facets candidate facets, each carrying the span it claims to derive
 *               from
 */
public record ExtractionResult(List<FacetCandidate> facets) {

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of());
    }
}
