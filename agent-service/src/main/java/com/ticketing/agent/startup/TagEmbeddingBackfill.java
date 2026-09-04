package com.ticketing.agent.startup;

import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.agent.vector.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every matchable tag a vector, built from its definition.
 *
 * <h3>Why the definition and not the slug</h3>
 * A slug is one or two words, and a vector built from that little text is
 * dominated by whichever tokens happen to be present rather than by meaning.
 * Measured against the query "a small room, close to the performer, only a
 * hundred people": embedding the slug {@code intimate} scored 0.556 and lost to
 * {@code live-music}; embedding name plus definition plus examples scored 0.819
 * and won outright.
 *
 * <p>The same pattern held for "a technical talk where I can learn and meet
 * people" — the slug matched {@code workshop}, the definition matched
 * {@code conference-tech}. Slug embeddings are string matching wearing a
 * disguise; they are right only where the query happens to reuse the word.
 *
 * <p>Length costs nothing here. The vector is computed once per tag and read on
 * every query thereafter.
 *
 * <h3>The only startup bean that touches the vocabulary</h3>
 * There is no seeder in front of this any more — the {@code tag} table is
 * written by reviewers, not by Java. This fills in whatever vector is missing,
 * so a definition edited through the curation API is re-embedded on the next
 * boot rather than left pointing at prose nobody wrote, and it reports the one
 * vocabulary defect a running system can have.
 *
 * <p>Skips dim-less tags. {@code headliner} and {@code late-night} are reachable
 * by exclusion only and are never compared against a facet, so a vector for them
 * would be dead weight — and worse, would let them win comparisons they should
 * not be in.
 */
@Slf4j
@Component
// Higher value runs later. 100 was the taxonomy seeder, now deleted; the slot
// is left empty rather than reused, so the backfill keeps the order the other
// runners' comments refer to.
@Order(200)
@RequiredArgsConstructor
public class TagEmbeddingBackfill implements ApplicationRunner {

    private final TagRepository    tagRepository;
    private final EmbeddingService embeddings;

    /**
     * The write needs a transaction; the network call must not be inside one.
     *
     * <p>An earlier version ran both outside and every row failed with
     * "Executing an update/delete query" — a {@code @Modifying} query has no
     * transaction to join. Wrapping the whole loop instead would hold a pooled
     * connection across fourteen embedding calls at startup, stalling every
     * other bean waiting on the pool.
     */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        int embedded = 0, skipped = 0, failed = 0;

        for (TagEntity tag : tagRepository.findAll()) {
            if (tag.getDim() == null) { skipped++; continue; }
            if (tag.getVectorSource() != null) { skipped++; continue; }

            String text = embeddingTextFor(tag);
            try {
                String vector = embeddings.embedDocument(text);   // outside any transaction
                tx.executeWithoutResult(st -> tagRepository.writeEmbedding(
                        tag.getId(), vector, "description", embeddings.modelVersion()));
                embedded++;
            } catch (Exception e) {
                // Ollama may not be up yet. Not fatal: the service still starts,
                // ingestion and the review screen still work, and the next boot
                // fills the gap. Failing startup over an optional backfill would
                // turn a degradation into an outage.
                log.warn("Could not embed tag '{}': {}", tag.getSlug(), e.getMessage());
                failed++;
            }
        }

        log.info("Tag embedding backfill: {} embedded, {} skipped, {} failed",
                embedded, skipped, failed);

        warnOnUnderpopulatedDims();
    }

    /**
     * A dim with exactly one tag cannot be matched, only defaulted to.
     *
     * <p>Matching is an argmax over the tags on a facet's dim, and an argmax
     * over a set of one has nothing to reject. Measured: {@code family-kids}
     * alone on {@code audience} took all seventeen audience facets and twelve
     * were wrong.
     *
     * <p>Read from the database, which is the only place the vocabulary exists.
     */
    private void warnOnUnderpopulatedDims() {
        tagRepository.findAll().stream()
                .filter(t -> t.getDim() != null)
                .collect(java.util.stream.Collectors.groupingBy(TagEntity::getDim,
                        java.util.stream.Collectors.counting()))
                .forEach((dim, n) -> {
                    if (n < 2) log.warn("Dim '{}' has only {} tag — every facet on it will be "
                            + "assigned that tag regardless of fit. Add a second, or give the "
                            + "tag no dim.", dim, n);
                });
    }

    /**
     * Built from the stored columns, which are now the only definition there is.
     *
     * <p>This used to prefer a Java constant of the same slug and fall back to
     * the row. The preference was the bug that made an edit through the
     * curation API invisible: the row changed, the vector was rebuilt from the
     * constant, and the reviewer's new wording never reached the space.
     *
     * <p>Byte-identical to {@code TagCurationService.embeddingTextOf}, so a tag
     * embedded at creation and one embedded by this backfill land on the same
     * vector.
     */
    private String embeddingTextFor(TagEntity tag) {
        return tag.getName() + ". " + tag.getDescription()
                + (tag.getExamples() == null ? "" : " " + tag.getExamples());
    }
}
