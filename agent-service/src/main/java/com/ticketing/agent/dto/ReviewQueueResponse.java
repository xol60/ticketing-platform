package com.ticketing.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What is waiting for a reviewer, and what nothing can answer.
 *
 * <p>Two lists rather than one, because they need opposite actions. A proposal
 * is a yes-or-no question the matcher has already framed. A gap is a facet no
 * tag covers at all, and the only move is to write a definition — or to accept
 * that the facet was filed on the wrong dim and no answer exists here.
 *
 * <p>Both were previously visible only by running SQL against the database,
 * which meant the flow had an approve endpoint, a reject endpoint and no way to
 * find out what to approve or reject.
 */
@Data
@Builder
public class ReviewQueueResponse {

    private String dim;

    /** Proposals with no verdict, strongest first. */
    private List<Proposal> proposals;

    /**
     * Approved facets on this dim that no tag covers.
     *
     * <p>"Covers" is the cross-dim comparison, not a threshold: a tag appears
     * for a facet only when it beats every tag on every other dim. A facet here
     * is either vocabulary the dim is missing, or a facet the model filed on
     * the wrong dim — and the reviewer is the one who can tell which.
     */
    private List<Gap> gaps;

    @Data
    @Builder
    public static class Proposal {
        private String eventId;
        private String eventName;
        private String tagSlug;
        private double score;
        /** The facet values on this dim for that event — the evidence to judge on. */
        private List<String> evidence;
    }

    @Data
    @Builder
    public static class Gap {
        private Long   facetId;
        private String eventId;
        private String eventName;
        private String value;
        private String sourceSpan;
        /** How many other facets on this dim carry the same value. */
        private int occurrences;
    }
}
