package com.ticketing.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** What creating this tag would do to the dim it lands on. */
@Data
@Builder
public class TagPreviewResponse {

    private String slug;
    private String dim;

    /** How many facets on the dim the new tag would become the top match for. */
    private int wouldWin;

    /** How many approved facets exist on that dim at all. */
    private int facetsOnDim;

    /** How many tags the dim will hold once this one lands. */
    private int tagsOnDimAfter;

    /**
     * True when the dim would end up with a single tag, which is not a
     * vocabulary. Matching is an argmax over the tags on a facet's dim, so one
     * tag means every facet is assigned it regardless of fit — measured,
     * {@code family-kids} alone on {@code audience} took all seventeen audience
     * facets and twelve were wrong.
     */
    private boolean singleTagWarning;

    /** The facets it would take, strongest first — the blast radius. */
    private List<Claim> claims;

    @Data
    @Builder
    public static class Claim {
        private String facetValue;
        private double newScore;
        private double currentBest;
    }
}
