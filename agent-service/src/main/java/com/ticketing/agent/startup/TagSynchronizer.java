package com.ticketing.agent.startup;

import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pushes {@link Taxonomy#TAGS} into the {@code tag} table at startup.
 *
 * <h3>Why this is code and not a seed migration</h3>
 * A migration full of INSERTs would be a second copy of the taxonomy, and the
 * two copies would eventually disagree — someone edits the Java description,
 * the row keeps the old text, and the bootstrap embedding is silently built
 * from prose nobody has read in months. Mirroring on every boot means Java
 * stays the single definition and the table is only ever its shadow.
 *
 * <h3>Deliberately non-destructive</h3>
 * Tags removed from {@link Taxonomy} are <em>not</em> deleted here. Rows in
 * {@code event_tag} reference them, and quietly dropping a tag would either
 * fail on the foreign key or, worse, cascade away human review decisions.
 * Retiring a tag is a deliberate migration, not a side effect of editing a
 * constant — so this logs the orphan and leaves it alone.
 *
 * <p>An edited description invalidates that tag's bootstrap vector, since the
 * vector was embedded from the old text. The row is marked by clearing
 * {@code vector_source}, which is what the embedding backfill looks for.
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(100)   // before TagEmbeddingBackfill
@RequiredArgsConstructor
public class TagSynchronizer implements ApplicationRunner {

    private final TagRepository tagRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0, updated = 0;

        for (Taxonomy.Tag def : Taxonomy.TAGS) {
            TagEntity row = tagRepository.findBySlug(def.slug()).orElse(null);

            if (row == null) {
                tagRepository.save(TagEntity.builder()
                        .slug(def.slug())
                        .name(def.name())
                        .description(def.description())
                        .dim(def.dim())
                        .examples(def.examples())
                        .source("taxonomy")
                        .build());
                created++;
                continue;
            }

            // A reviewer-added tag has no Java counterpart and must survive
            // every restart untouched.
            if ("human".equals(row.getSource())) continue;

            boolean textChanged = !def.description().equals(row.getDescription())
                    || !java.util.Objects.equals(def.examples(), row.getExamples());
            boolean metadataChanged = !def.name().equals(row.getName())
                    || !java.util.Objects.equals(def.dim(), row.getDim());

            if (textChanged || metadataChanged) {
                row.setName(def.name());
                row.setDim(def.dim());
                row.setExamples(def.examples());
                row.setDescription(def.description());

                if (textChanged && "description".equals(row.getVectorSource())) {
                    // The stored vector was embedded from text that no longer
                    // exists. Leaving it would mean snapping labels against a
                    // definition nobody wrote — clear the marker so the
                    // backfill re-embeds it.
                    row.setVectorSource(null);
                    log.info("Tag '{}' text changed — vector invalidated, will re-embed", def.slug());
                }
                tagRepository.save(row);
                updated++;
            }
        }

        List<String> orphans = tagRepository.findAll().stream()
                .map(TagEntity::getSlug)
                .filter(slug -> !Taxonomy.isKnownTag(slug))
                .toList();

        if (!orphans.isEmpty()) {
            log.warn("Tags present in the database but absent from Taxonomy: {}. "
                    + "Left in place — event_tag rows still reference them. "
                    + "Retire one with a migration, not by deleting the constant.", orphans);
        }

        log.info("Tag sync complete: {} created, {} updated, {} total in Taxonomy",
                created, updated, Taxonomy.TAGS.size());
    }
}
