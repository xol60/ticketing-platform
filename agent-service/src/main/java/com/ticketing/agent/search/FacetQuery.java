package com.ticketing.agent.search;

/**
 * One dimension of what the user is asking for, in their own terms.
 *
 * <p>Compared only against event facets on the <em>same</em> dim. Mixing dims
 * into a single comparison is what produces a flat cosine around 0.3 across an
 * entire dataset — "calm and unhurried" is not more or less similar to "a
 * seated theatre" than to "a standing gig", because they answer different
 * questions.
 *
 * @param dim   one of the eight names in the closed vocabulary
 * @param value the phrase to embed, already stripped of anything SQL can
 *              decide exactly — no city, no date, no price, and above all no
 *              negation
 */
public record FacetQuery(String dim, String value) {}
