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
        String properNoun,
        String city,
        String dateExpression,
        BigDecimal priceMax,
        List<FacetQuery> vibeFacets,
        List<String> excludeTags) {

    public static QueryExtraction empty() {
        return new QueryExtraction(null, null, null, null, List.of(), List.of());
    }

    /**
     * True when the person named something specific rather than describing a
     * mood — a lookup, not a browse. The two need different defaults.
     */
    public boolean isLookup() {
        return properNoun != null && !properNoun.isBlank();
    }

    /** True when nothing was said that narrows anything — the turn-1 case. */
    public boolean isBare() {
        return properNoun == null && city == null && dateExpression == null
                && priceMax == null && vibeFacets.isEmpty() && excludeTags.isEmpty();
    }
}
