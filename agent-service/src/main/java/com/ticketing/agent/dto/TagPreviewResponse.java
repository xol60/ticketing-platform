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

    /**
     * Whether this call actually wrote the tag.
     *
     * <p>False on every preview, and false on a create that was refused for
     * duplicating an existing tag. A refused create returns this same body
     * rather than a bare error, because the reviewer's next move depends on
     * what it contains: either re-send with {@code acknowledgeDuplicate}, or
     * attach the tag they already have.
     */
    private boolean created;

    /**
     * True when the candidate duplicates a tag the vocabulary already has.
     *
     * <p>Either of two tests fires it. It <b>takes over</b>: an existing tag
     * holding three or more facets loses half of them to the candidate.
     * Or it <b>reads the same</b>: the candidate sits closer to an existing tag
     * than any two existing tags on that dim sit to each other.
     *
     * <p>The second test uses no constant, and could not — definitions that are
     * unmistakably distinct score up to 0.721 against each other on this corpus
     * ({@code team-sport-fixture} against {@code combat-sport} is 0.716), so a
     * fixed cut either flags everything or nothing. See
     * {@link #similarityBaseline}.
     */
    private boolean duplicateWarning;

    /**
     * The closest any two existing tags on this dim sit to each other, which is
     * the bar the candidate's wording is judged against.
     *
     * <p>Null when the dim holds fewer than two tags — no pair, no baseline,
     * and displacement decides alone.
     */
    private Double similarityBaseline;

    /** Every tag on the dim, with what the candidate would take from it. */
    private List<Overlap> overlaps;

    @Data
    @Builder
    public static class Claim {
        private String facetValue;
        private double newScore;
        private double currentBest;
    }

    /** One existing tag, and how much of it the candidate would absorb. */
    @Data
    @Builder
    public static class Overlap {
        private String slug;
        private String name;

        /** Facets this tag currently wins at rank one on the dim. */
        private int held;

        /** How many of those the candidate would take. */
        private int taken;

        /**
         * Cosine between the two definitions, read against
         * {@link TagPreviewResponse#similarityBaseline} rather than any fixed
         * number.
         */
        private double textSimilarity;

        /** True when this is a tag the candidate duplicates, by either test. */
        private boolean displaced;
    }

}
