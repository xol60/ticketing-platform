package com.ticketing.agent.startup;

import com.ticketing.agent.domain.model.EventFacet;
import com.ticketing.agent.domain.repository.EventFacetRepository;
import com.ticketing.agent.ingest.TagSuggester;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.vector.TagMatcher;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Gives a vector to facets sitting on a dim that has since become embedded.
 *
 * <h3>Why this exists rather than a re-ingest</h3>
 * Widening {@link Taxonomy#EMBEDDED_DIMS} leaves every facet already stored on
 * the newly-added dims without a vector, and a facet without a vector is
 * invisible to both the dim gate and tag matching. Re-running ingestion would
 * fix that, but it would also re-run the language model over every event and
 * produce a <em>different</em> set of facets — the extraction is not
 * deterministic. The facets that survived validation would be discarded and
 * replaced by ones that had not, and any review already done on them would be
 * lost for no gain.
 *
 * <p>The facets are not what changed. Only the decision about which dims carry
 * vectors changed. So this backfills exactly that and touches nothing else:
 * same rows, same text, same spans, one embedding call each.
 *
 * <h3>Approval is left alone</h3>
 * These rows were approved on the four deterministic gates, under a rule that
 * said the dim gate could not apply to them. Re-gating them now would be
 * arbitrary: the gate compares a facet against approved, embedded facets per
 * dim, and while this loop is running its own dims are still filling up, so the
 * verdict a facet receives would depend on where it happened to fall in the
 * ordering. The gate applies to the next ingest, when the baseline is complete.
 *
 * <h3>Idempotent</h3>
 * Only rows with a null embedding are read, so a second boot finds nothing and
 * does nothing.
 */
@Slf4j
@Component
@Order(300)   // after TagEmbeddingBackfill (200):
              // tags must have their own vectors before anything is matched
              // against them, or every match here would silently find nothing.
@RequiredArgsConstructor
public class FacetEmbeddingBackfill implements ApplicationRunner {

    private final EventFacetRepository facetRepository;
    private final EmbeddingService     embeddings;
    private final TagMatcher           tagMatcher;
    private final TagSuggester         tagSuggester;
    private final TransactionTemplate  tx;

    @Override
    public void run(ApplicationArguments args) {
        List<EventFacet> pending =
                facetRepository.findUnembeddedOn(Taxonomy.EMBEDDED_DIMS);

        if (pending.isEmpty()) {
            log.debug("Facet embedding backfill: nothing pending");
            return;
        }

        log.info("Facet embedding backfill: {} facets on embedded dims have no vector", pending.size());
        int embedded = 0, suggested = 0, failed = 0;

        for (EventFacet facet : pending) {
            try {
                // Value and span together, matching ingestion exactly. A value
                // alone is often one or two words with too little signal to
                // place; embedding it differently here would mean the same
                // facet sits at two different points depending on which code
                // path wrote it.
                String vector = embeddings.embedDocument(
                        TagMatcher.representationOf(facet.getValue(), facet.getSourceSpan()));

                var tags = tagMatcher.candidatesFor(facet.getDim(), vector,
                        TagSuggester.CANDIDATES_PER_FACET);      // outside the transaction

                tx.executeWithoutResult(s -> {
                    facetRepository.writeEmbedding(facet.getId(), vector, embeddings.modelVersion());
                    tagSuggester.record(facet.getEventId(), facet.getId(), tags);
                });

                embedded++;
                if (!tags.isEmpty()) suggested++;
            } catch (Exception e) {
                // Ollama being down is not a reason to fail startup. The rows
                // keep their null embedding and the next boot retries them.
                log.warn("Could not embed facet {} ('{}' on {}): {}",
                        facet.getId(), facet.getValue(), facet.getDim(), e.getMessage());
                failed++;
            }
        }

        log.info("Facet embedding backfill: {} embedded, {} produced a tag suggestion, {} failed",
                embedded, suggested, failed);
    }
}
