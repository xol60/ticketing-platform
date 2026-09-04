package com.ticketing.agent.startup;

import com.ticketing.agent.domain.repository.FacetRejectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns overturned gate decisions back into facets.
 *
 * <h3>Why the overlap gate needs an appeal and the grounding gate does not</h3>
 * The overlap gate asks whether a facet fairly restates the span it cites, and
 * answers with a word-overlap ratio against a threshold. That is a heuristic,
 * and heuristics are wrong in both directions:
 *
 * <ul>
 *   <li>{@code "traditional and ceremonial"} cited "Wimbledon's <b>traditions</b>
 *       are as iconic as its tennis" and scored 0.00, because the stemmer does
 *       not relate <i>traditions</i> to <i>traditional</i>.</li>
 *   <li>{@code "two days"} cited "The 2026 edition (<b>May 19-20</b>)" and
 *       scored 0.00. The inference is correct and word overlap cannot make it
 *       at all.</li>
 * </ul>
 *
 * Grounding is different in kind. It asks whether the quoted text exists, which
 * is not a matter of degree and not a judgement — so it has no appeal, enforced
 * by a check constraint rather than by convention.
 *
 * <h3>Runs before the backfills</h3>
 * A promoted facet has no vector and no candidate list. Both follow from the
 * ordinary backfills at order 300 and 400, so this has to land first.
 */
@Slf4j
@Component
@Order(250)
@RequiredArgsConstructor
public class RejectionOverridePromoter implements ApplicationRunner {

    private final FacetRejectionRepository rejectionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int promoted = rejectionRepository.promoteOverridden();
        if (promoted > 0) {
            log.info("Promoted {} overridden rejection(s) back to facets", promoted);
        }
    }
}
