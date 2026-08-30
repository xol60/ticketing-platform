package com.ticketing.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What a search turn returns.
 *
 * <p>Every field here is read from the database. The model never emits a fact
 * about an event — no time, no price, no venue — because a model that writes a
 * showtime will eventually write a wrong one, and a wrong showtime is worse
 * than no answer.
 */
@Data
@Builder
public class SearchResponse {

    private List<Hit> hits;

    /**
     * How many events matched, not how many are shown. Attached so a follow-up
     * question can carry a real number: "narrow down" means something different
     * against 12 matches than against 380.
     */
    private long totalMatched;

    /** True when {@link #totalMatched} is large enough that narrowing is worth offering. */
    private boolean offerNarrowing;

    /**
     * What was widened to find these, in the order it happened. Empty on a
     * normal search. Surfaced because a result set that silently ignored the
     * stated budget is worse than an empty one.
     */
    private List<String> relaxations;

    /** False when ranking fell back to popularity and proximity with no vibe signal. */
    private boolean usedVibe;

    @Data
    @Builder
    public static class Hit {
        private String eventId;
        private String name;
        private String primaryArtist;
        private String venueName;
        private String venueCity;
        private String category;
        private Instant startAt;
        private BigDecimal priceMin;
        private BigDecimal priceMax;

        /**
         * The distilled facets that differ most across this result set — what
         * the design uses instead of a per-result explanation.
         *
         * <p>An explanation would have to invent a reason the ranker cannot
         * supply, which is a surface for the model to make things up on. Showing
         * the fields that actually vary lets the reader see the difference
         * rather than be told about it, and costs no tokens.
         */
        private List<String> differentiators;

        /** Ranking score, for telemetry. Not meaningful to a person. */
        private double score;
    }
}
