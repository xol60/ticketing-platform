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

    /** One event with the score that put it here, kept for telemetry and debugging. */
    public record Scored(AgentEvent event, double score, double semantic) {}

    public static SearchResult empty(List<String> relaxations) {
        return new SearchResult(List.of(), 0, relaxations, false);
    }
}
