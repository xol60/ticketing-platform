package com.ticketing.agent.search;

import com.ticketing.agent.domain.model.AgentEvent;

import java.util.List;

/**
 * What one search turn produced.
 *
 * @param events        the ranked shortlist, already diversity-constrained
 * @param totalMatched  how many cleared the hard filter — the honest count, not
 *                      the shortlist size. Offering to narrow without it is
 *                      asking a question nobody can answer: "narrow down" means
 *                      something different against 12 results than against 380
 * @param relaxations   what was widened, in order, when nothing matched as asked.
 *                      Empty on a normal search. Never silent: a result set that
 *                      quietly ignored the stated budget is worse than none
 * @param usedVibe      whether the vector path contributed. False means ranking
 *                      fell back to popularity and proximity, which is worth
 *                      knowing when the results look generic
 */
public record SearchResult(
        List<Scored> events,
        long totalMatched,
        List<String> relaxations,
        boolean usedVibe) {

    /**
     * One event with the score that put it here.
     *
     * @param matched whether this event actually answers what was asked, as
     *                opposed to merely surviving the filter and ranking above
     *                the rest. False marks a row the shortlist reached for to
     *                fill a slot.
     *                <p>Only meaningful when the request resolved to a tag —
     *                membership is then a reviewed fact with a real zero.
     *                Cosine has no zero (two unrelated phrases on one dim score
     *                0.452), so where the request could only be scored by
     *                cosine every row is reported as matched rather than split
     *                on a boundary that does not exist.
     */
    public record Scored(AgentEvent event, double score, double semantic, boolean matched) {

        public Scored(AgentEvent event, double score, double semantic) {
            this(event, score, semantic, true);
        }
    }

    /** Rows that answer the request, as opposed to rows filling the shortlist. */
    public long matchedCount() {
        return events().stream().filter(Scored::matched).count();
    }

    public static SearchResult empty(List<String> relaxations) {
        return new SearchResult(List.of(), 0, relaxations, false);
    }
}
