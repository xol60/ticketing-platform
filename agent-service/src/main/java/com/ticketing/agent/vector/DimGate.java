package com.ticketing.agent.vector;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.EventFacetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The fifth gate: does this facet look like the dim it was filed under?
 *
 * <h3>What it catches that the deterministic gates cannot</h3>
 * The four gates before this one establish that a facet was genuinely read
 * from the source and does not contradict the record. None of them look at
 * whether it was <em>filed</em> correctly. A facet quoting a real sentence,
 * restating it accurately, and consistent with the ticket count can still be
 * atmosphere content sitting in the format slot — and that is a mistake the
 * local model makes constantly. Measurement on qwen3:8b showed every facet for
 * one event landing under a single dim until the prompt was changed.
 *
 * <h3>Why it reviews rather than rejects</h3>
 * The comparison is against facets already approved on the same dim, so it is
 * only as meaningful as the history behind it. On a dim with three approved
 * rows the centroid is noise, and a genuinely novel-but-correct facet looks
 * exactly like a misfiled one. Rejecting on that would quietly delete good
 * facets from a corpus that has few — so a low score means unapproved and
 * visible, not gone.
 *
 * <p>Runs last among the gates because it is the only one that costs an
 * embedding call, and by this point the candidate has already survived
 * everything cheap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DimGate {

    private final EventFacetRepository facetRepository;
    private final AgentProperties      properties;

    /**
     * @param vectorLiteral the facet's embedding, already computed for storage
     * @return true when the facet may be auto-approved on this dim; false when
     *         it should wait for a human
     */
    public boolean looksLikeDim(String dim, String vectorLiteral) {
        Double similarity = facetRepository.meanSimilarityWithinDim(dim, vectorLiteral);

        // Null means no approved facet exists on this dim yet. Validation
        // cannot run, and "cannot decide" is not "passed" — the facet goes to
        // review, which is also how the first few facets on any dim become the
        // baseline everything after them is measured against.
        if (similarity == null) {
            log.debug("Dim '{}' has no approved facets yet — routing to review", dim);
            return false;
        }

        double threshold = properties.getValidation().getDimThreshold();
        boolean pass = similarity >= threshold;

        if (!pass) {
            log.debug("Facet on dim '{}' scored {} against approved facets, below {}",
                    dim, similarity, threshold);
        }
        return pass;
    }
}
