package com.ticketing.agent.startup;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.FacetTagCandidateRepository;
import com.ticketing.agent.ingest.TagSuggester;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives already-stored facets their candidate lists.
 *
 * <p>Needed once, when matching stopped keeping only the winning tag and
 * started keeping the shortlist. The facets and their vectors were already
 * correct; only the record of what they were compared against was missing, and
 * re-ingesting to recover it would have re-run the language model over every
 * event and produced a different, unreviewed set of facets in exchange for
 * information Postgres could compute from what it already held.
 *
 * <p>Also covers the ordinary case of a tag being added after ingestion: clear
 * the candidate rows for that dim and the next boot rebuilds them.
 */
@Slf4j
@Component
@Order(400)   // after tags are synced (100) and embedded (200), and after
              // facets have their own vectors (300) — this compares the two.
@RequiredArgsConstructor
public class FacetCandidateBackfill implements ApplicationRunner {

    private final FacetTagCandidateRepository candidateRepository;
    private final AgentProperties             properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int written = candidateRepository.backfillMissing(TagSuggester.CANDIDATES_PER_FACET);
        if (written > 0) {
            log.info("Facet candidate backfill: {} candidate rows written", written);
        }
    }
}
