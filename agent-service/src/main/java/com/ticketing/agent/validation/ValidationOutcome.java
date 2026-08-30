package com.ticketing.agent.validation;

/**
 * What the deterministic gates decided about one candidate facet.
 *
 * <h3>Three outcomes, not two</h3>
 * The middle one carries the weight. A gate that can only accept or reject
 * forces every heuristic to be treated as certain, and the honest state of
 * most checks is "this looks wrong, but I cannot prove it":
 *
 * <ul>
 *   <li>{@link Verdict#REJECT} is reserved for checks that are <em>decidable</em>
 *       — the span is absent from the source, or the claim contradicts a fact
 *       the database derived itself. Rejected facets never reach
 *       {@code event_facet}.</li>
 *   <li>{@link Verdict#REVIEW} is for suspicion. The facet is stored unapproved,
 *       so it is visible and correctable but cannot affect a search result
 *       until a person says so.</li>
 *   <li>{@link Verdict#ACCEPT} means every deterministic gate passed.</li>
 * </ul>
 *
 * <p>Getting this split wrong is expensive in both directions: rejecting on a
 * heuristic quietly deletes real facets from events that had few to begin
 * with, and reviewing what should be rejected buries the queue in noise until
 * nobody reads it.
 */
public record ValidationOutcome(
        FacetCandidate candidate,
        Verdict verdict,
        RejectionReason reason,
        String detail) {

    public enum Verdict { ACCEPT, REVIEW, REJECT }

    public static ValidationOutcome accept(FacetCandidate c) {
        return new ValidationOutcome(c, Verdict.ACCEPT, null, null);
    }

    /** Store unapproved, with a note for whoever works the queue. */
    public static ValidationOutcome review(FacetCandidate c, String detail) {
        return new ValidationOutcome(c, Verdict.REVIEW, null, detail);
    }

    public static ValidationOutcome reject(FacetCandidate c, RejectionReason reason, String detail) {
        return new ValidationOutcome(c, Verdict.REJECT, reason, detail);
    }

    public boolean rejected() { return verdict == Verdict.REJECT; }
    public boolean accepted() { return verdict == Verdict.ACCEPT; }
}
