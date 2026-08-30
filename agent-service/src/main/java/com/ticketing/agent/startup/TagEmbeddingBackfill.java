package com.ticketing.agent.startup;

import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.common.agent.Taxonomy;
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
 * <h3>Runs after TagSynchronizer</h3>
 * The synchroniser writes rows and clears {@code vector_source} on any whose
 * text changed. This fills in whatever is missing, so an edited definition is
 * re-embedded on the next boot rather than left pointing at prose nobody wrote.
 *
 * <p>Skips dim-less tags. {@code headliner} and {@code late-night} are reachable
 * by exclusion only and are never compared against a facet, so a vector for them
 * would be dead weight — and worse, would let them win comparisons they should
 * not be in.
 */
@Slf4j
@Component
// Higher value runs later. TagSynchronizer is at 100 and must write dim and
// clear vector_source before this reads them — with the two the wrong way round
// the backfill saw fourteen rows with a null dim and skipped every one.
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
    }

    /**
     * Prefers the Java definition, which is authoritative for taxonomy tags and
     * carries the examples. Falls back to the stored columns for reviewer-added
     * tags, which have no Java counterpart.
     */
    private String embeddingTextFor(TagEntity tag) {
        return Taxonomy.TAGS.stream()
                .filter(t -> t.slug().equals(tag.getSlug()))
                .findFirst()
                .map(Taxonomy.Tag::embeddingText)
                .orElseGet(() -> tag.getName() + ". " + tag.getDescription()
                        + (tag.getExamples() == null ? "" : " " + tag.getExamples()));
    }
}
