package com.ticketing.agent.vector;

import com.ticketing.agent.domain.repository.EventFacetRepository;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The fifth gate: is this facet filed under the right dim?
 *
 * <h3>A comparison, not a threshold</h3>
 * The first version asked whether a facet was similar <em>enough</em> to the
 * average approved facet on its own dim, and that question has no good answer.
 * Dims are legitimately diverse: approved {@code format} facets included
 * "stadium tour", "keynote presentations" and "football match", so a correctly
 * filed fourth one scored low against their mean and was held back. 67 of 77
 * format facets failed a test they should have passed, purely for being
 * different from each other.
 *
 * <p>What the gate is actually for is narrower — catching atmosphere content
 * written into the format slot. That is a comparative question: does this look
 * more like its own dim than like any other? An argmax answers it, needs no
 * threshold to tune, and stops punishing a dim for its own variety.
 *
 * <h3>Abstaining while the evidence is thin</h3>
 * The comparison only means something once every embedded dim has approved
 * facets to speak for it. Before that, a genuine atmosphere facet would be
 * compared against format alone and lose by default — a dim with nothing
 * approved cannot win an argmax. So until all embedded dims clear
 * {@link #BOOTSTRAP_FLOOR}, the gate abstains and admits on the four
 * deterministic gates alone, which is a real bar: grounded in the source, on
 * topic, not contradicted by the record.
 *
 * <h3>Review, never reject</h3>
 * A facet that loses the argmax is stored unapproved, not discarded. The
 * evidence is only as good as its history, and a novel-but-correct facet looks
 * much like a misfiled one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DimGate {

    /**
     * Approved facets each embedded dim needs before the comparison is
     * trustworthy.
     *
     * <p>Deliberately small. Under the old threshold test the floor was doing
     * two jobs — bootstrapping and gating — and it did the second badly. Here
     * it only has to ensure every dim has a voice in the argmax.
     */
    static final long BOOTSTRAP_FLOOR = 5;

    private final EventFacetRepository facetRepository;

    /**
     * @param vectorLiteral the facet's embedding, already computed for storage
     * @return true when the facet may be auto-approved on this dim
     */
    public boolean looksLikeDim(String dim, String vectorLiteral) {
        if (!allEmbeddedDimsHaveEvidence()) {
            log.debug("Not every embedded dim has {} approved facets yet — "
                    + "admitting '{}' on the deterministic gates alone", BOOTSTRAP_FLOOR, dim);
            return true;
        }

        List<Object[]> perDim = facetRepository.bestSimilarityPerDim(vectorLiteral);
        if (perDim.isEmpty()) return true;

        String bestDim = (String) perDim.get(0)[0];
        boolean pass = bestDim.equals(dim);

        if (!pass) {
            log.debug("Facet filed under '{}' looks more like '{}' (sim {}) — routing to review",
                    dim, bestDim, perDim.get(0)[1]);
        }
        return pass;
    }

    private boolean allEmbeddedDimsHaveEvidence() {
        return Taxonomy.EMBEDDED_DIMS.stream()
                .allMatch(d -> facetRepository.countByDimAndApprovedAtIsNotNull(d) >= BOOTSTRAP_FLOOR);
    }
}
