package com.ticketing.agent.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one conversation remembers between turns.
 *
 * <h3>Hard slots persist, vibe does not accumulate</h3>
 * City, date and price carry forward until the person changes or clears them —
 * "in london" on turn two is a refinement, not a new search.
 *
 * <p>A described mood behaves differently: a turn that expresses one
 * <em>replaces</em> whatever came before, and a turn that expresses none leaves
 * it standing. Appending would be the obvious implementation and is the wrong
 * one — "something relaxing" on turn one and "actually more upbeat" on turn
 * three would sum into a vector pointing between the two, matching nothing the
 * person ever wanted. Replacing keeps the latest intent whole; keeping on
 * silence lets "in london" narrow an existing mood instead of erasing it.
 *
 * <h3>Losing this is not a failure</h3>
 * State lives in Redis under a TTL and nothing here is durable. A dropped
 * conversation costs the person a re-typed sentence; no order, payment or
 * reservation depends on any of it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationState {

    public enum Stage {
        /** Searching. The full pipeline runs. */
        BROWSING,
        /**
         * The person picked one event and is asking about it. The hard filter
         * collapses to that id and the vector path is skipped entirely —
         * "what time does it start" is a database lookup, and running it
         * through semantic matching would be both slower and wrong.
         */
        FOCUSED,
        /** Ready to hand off to checkout. */
        CONFIRMING
    }

    private String sessionId;

    @Builder.Default
    private Stage stage = Stage.BROWSING;

    /** Set while {@link Stage#FOCUSED}. */
    private String focusedEventId;

    /**
     * What the last turn showed, in display order.
     *
     * <p>This is what "the second one" resolves against. The lookup is an array
     * index in Java, never a question to the model: asked to recall which event
     * was second, a model will answer confidently and drift as the conversation
     * lengthens, and the failure is silent — the person gets details for an
     * event they did not point at.
     */
    @Builder.Default
    private List<String> candidateEventIds = new ArrayList<>();

    // ── Hard slots, carried forward ──────────────────────────────────────────
    private String     city;
    private String     dateExpression;
    private BigDecimal priceMax;

    @Builder.Default
    private List<String> excludeTags = new ArrayList<>();

    /**
     * The mood from the most recent turn that expressed one. Stored as
     * {@code dim|value} pairs to keep the Redis payload flat.
     */
    @Builder.Default
    private List<String> vibeFacets = new ArrayList<>();

    @Builder.Default
    private int turnCount = 0;

    public boolean isFirstTurn() {
        return turnCount == 0;
    }
}
