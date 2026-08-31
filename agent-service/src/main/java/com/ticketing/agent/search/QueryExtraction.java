package com.ticketing.agent.search;

import java.math.BigDecimal;
import java.util.List;

/**
 * One user message, split into the three kinds of signal that travel different
 * paths.
 *
 * <p>The split is the whole design, and getting it wrong is subtle rather than
 * loud:
 *
 * <ul>
 *   <li><b>Hard slots</b> — city, date, price. SQL decides these exactly, so
 *       they must never reach a vector. "new york" inside an embedding happily
 *       matches a Boston event whose copy says "unlike the crowded New York
 *       scene": a lexical accident that returns a confidently wrong result.</li>
 *   <li><b>Vibe facets</b> — the part only a vector can answer.</li>
 *   <li><b>Exclusions</b> — tag slugs, resolved to {@code NOT EXISTS}.
 *       Negation can <em>never</em> be embedded. "not too crowded" lands
 *       <em>next to</em> "crowded" in vector space, not far from it, because
 *       embeddings have no notion of negation. Left in the vibe text it
 *       returns precisely the events the user just ruled out.</li>
 * </ul>
 *
 * @param intent          what the person is doing this turn. Inferred from the
 *                        sentence, and the only reliable way to tell a question
 *                        about the chosen event from a new search — asking
 *                        "what time does it start" reliably produced a spurious
 *                        duration facet, which made the turn look like a search
 *                        and lost the event the person had just picked
 * @param ordinal         1-based position the person pointed at — "the second
 *                        one", "the first". Resolved against the previous
 *                        turn's list in Java, never by the model: asked to
 *                        recall what was second, a model answers confidently
 *                        and drifts as the conversation lengthens, and the
 *                        person silently gets details for the wrong event
 * @param clearFields     slots the person removed — "forget the budget",
 *                        "anywhere is fine". Distinct from simply not
 *                        mentioning a slot, which leaves it standing
 * @param properNoun      an artist, venue or event name the person named. Kept
 *                        whole and never distilled: a name is matched
 *                        literally, and embedding "Taylor Swift" into a vibe
 *                        vector turns a lookup into a mood search. Its presence
 *                        also changes the default date window — someone naming
 *                        an artist wants that artist's shows, not only the ones
 *                        in the next fortnight
 * @param city            free text as the user wrote it; resolved to a city id in Java
 * @param dateExpression  a relative phrase like "this weekend" — never a
 *                        computed date. Date arithmetic belongs to Java, with a
 *                        real clock and a real zone; a model asked to compute
 *                        one will confidently produce a plausible wrong day
 * @param priceMax        upper bound, or null for no constraint
 * @param vibeFacets      what to match semantically
 * @param excludeTags     catalogue slugs the user ruled out
 */
public record QueryExtraction(
        Intent intent,
        Integer ordinal,
        List<String> clearFields,
        String properNoun,
        String city,
        String dateExpression,
        BigDecimal priceMax,
        List<FacetQuery> vibeFacets,
        List<String> excludeTags) {

    /**
     * Enforces the one invariant the model keeps breaking: pointing at a row
     * retracts nothing.
     *
     * <p>Asked for "the second one", qwen3:8b reliably returns
     * {@code ordinal: 2} together with {@code clearFields: ["city",
     * "dateExpression", "priceMax"]} — reading the absence of those slots in a
     * three-word sentence as a request to drop them. An explicit counter-example
     * in the prompt did not stop it, and it would silently wipe a conversation's
     * accumulated filters on every selection.
     *
     * <p>Handled here rather than in the prompt because it is decidable: a
     * selection is not a retraction, whatever the model believes.
     */
    public QueryExtraction {
        if (ordinal != null && ordinal > 0 && !clearFields.isEmpty()) {
            clearFields = List.of();
        }

        // A slot the same turn states cannot also be a slot that turn retracts.
        //
        // The model does both at once. "actually in tokyo" comes back as
        // city="tokyo" together with clearFields=["city"], and because merge
        // applies the value first and the retraction second, the retraction
        // won: the city was extracted correctly, written to the conversation,
        // and erased in the same call. Three turns of a conversation returned
        // byte-identical results — Toronto races for a request about Tokyo —
        // with nothing in the logs to say a filter had ever been set.
        //
        // Decidable here, like the ordinal rule above, so it does not depend on
        // the order two lines happen to sit in inside merge().
        if (!clearFields.isEmpty()) {
            List<String> stated = new java.util.ArrayList<>(4);
            if (city != null)            stated.add("city");
            if (dateExpression != null)  stated.add("dateExpression");
            if (priceMax != null)        stated.add("priceMax");
            if (!excludeTags.isEmpty())  stated.add("excludeTags");
            if (!stated.isEmpty()) {
                clearFields = clearFields.stream().filter(f -> !stated.contains(f)).toList();
            }
        }
    }

    public static QueryExtraction empty() {
        return new QueryExtraction(Intent.FIND, null, List.of(), null, null, null, null, List.of(), List.of());
    }

    /**
     * True when the person named something specific rather than describing a
     * mood — a lookup, not a browse. The two need different defaults.
     */
    public boolean isLookup() {
        return properNoun != null && !properNoun.isBlank();
    }

    /** True when nothing was said that narrows anything — the turn-1 case. */
    public enum Intent {
        /** Looking for something. The full pipeline runs. */
        FIND,
        /** Pointing at a row already shown. */
        SELECT,
        /** Asking about the event already chosen — a database lookup, not a search. */
        DETAIL,
        /** Asking how two or three of the results differ. */
        COMPARE,
        /** Ready to buy the event they have chosen. The end of the funnel. */
        HANDOFF
    }

    /** True when the person pointed at a row rather than describing anything. */
    public boolean isOrdinalReference() {
        return ordinal != null && ordinal > 0;
    }

    public boolean isBare() {
        return ordinal == null && clearFields.isEmpty()
                && properNoun == null && city == null && dateExpression == null
                && priceMax == null && vibeFacets.isEmpty() && excludeTags.isEmpty();
    }
}
