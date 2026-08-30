package com.ticketing.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * One conversational turn's answer.
 *
 * <p>Carries either a shortlist or a single focused event, never both — the two
 * correspond to the two things a turn can be: a search, or a question about one
 * event already chosen.
 */
@Data
@Builder
public class ChatResponse {

    /** BROWSING, FOCUSED or CONFIRMING. Exposed so a client can render differently. */
    private String stage;

    /** Populated while browsing. */
    private List<SearchResponse.Hit> hits;

    /** Populated once the person has picked one — by ordinal, or by naming it. */
    private SearchResponse.Hit focused;

    private long totalMatched;
    private boolean offerNarrowing;
    private List<String> relaxations;
    private boolean usedVibe;

    /**
     * What the conversation currently believes, echoed back.
     *
     * <p>Not decoration. Slots accumulate silently across turns, and a search
     * narrowed by a constraint from four turns ago is indistinguishable from a
     * broken one unless the constraint is visible. This is also the cheapest
     * way for a person to notice they need to say "anywhere is fine".
     */
    private ActiveFilters activeFilters;

    /**
     * Present only on the turn the person asks to buy. Carries the event id and
     * the link to the existing event page — the seam where this subsystem ends
     * and the ordinary checkout begins.
     */
    private HandoffInfo handoff;

    @Data
    @Builder
    public static class HandoffInfo {
        private String eventId;
        private String deepLink;
        /** Courtesy check against the projection; checkout re-validates for real. */
        private boolean available;
        private String reason;
    }

    @Data
    @Builder
    public static class ActiveFilters {
        private String city;
        private String dateExpression;
        private String priceMax;
        private List<String> excludeTags;
        private List<String> vibe;
        private int turnCount;
    }
}
