package com.ticketing.agent.curation;

import com.ticketing.agent.domain.model.EventTag;
import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.domain.repository.*;
import com.ticketing.agent.dto.NewTagRequest;
import com.ticketing.agent.dto.TagPreviewResponse;
import com.ticketing.agent.ingest.TagSuggester;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.vector.TagCatalog;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adding a tag to the live vocabulary.
 *
 * <h3>Why this is a service and not a migration</h3>
 * The vocabulary is meant to grow from what the data turns out to need. A
 * reviewer meets a facet nothing covers, writes a tag, and it takes effect —
 * without a code change, a release, or a schema edit. {@code tag.source =
 * 'human'} is what protects such a row from the startup synchroniser, and
 * {@link TagCatalog} is what makes the query side read the live table rather
 * than the Java seed.
 *
 * <h3>The six steps, and why none can be skipped</h3>
 * <ol>
 *   <li>Write the row, marked {@code human}.</li>
 *   <li>Embed it from name + description + examples. Not the slug: measured,
 *       the slug {@code intimate} scored 0.556 and lost a query it should have
 *       won, the full definition scored 0.819 and won it.</li>
 *   <li>Clear every candidate list on the dim. They were scored against a set
 *       of tags that no longer exists.</li>
 *   <li>Rebuild them. Without this the reviewer sees an unchanged screen and
 *       concludes the edit did nothing.</li>
 *   <li>Invalidate the catalogue cache, or the tag stays unnameable in
 *       {@code excludeTags} until the next restart.</li>
 *   <li>Attach it to the event that prompted it. This is what guarantees a tag
 *       created here is never empty — the failure that retired six seed
 *       tags.</li>
 * </ol>
 *
 * <p>Steps 3 to 5 were all missing when this flow was first attempted by hand,
 * and each one silently produces a tag that looks created and does nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagCurationService {

    private final TagRepository               tagRepository;
    private final EventFacetRepository        facetRepository;
    private final EventTagRepository          eventTagRepository;
    private final FacetTagCandidateRepository candidateRepository;
    private final EmbeddingService            embeddings;
    private final TagCatalog                  tagCatalog;
    private final TransactionTemplate         tx;

    /** Shown before anything is written. See {@link TagPreviewResponse}. */
    public TagPreviewResponse preview(NewTagRequest req) {
        validate(req, false);
        String vector = embeddings.embedQuery(embeddingTextOf(req));   // outside any transaction
        return buildPreview(req, vector);
    }

    /**
     * Creates the tag and rebuilds everything that depended on the old vocabulary.
     *
     * @return the same preview, recomputed after the write, so the caller sees
     *         what actually happened rather than what was predicted
     */
    public TagPreviewResponse create(NewTagRequest req) {
        validate(req, true);

        // The network call stays outside the transaction. An earlier version of
        // a sibling flow held one across an embedding call and exhausted the
        // connection pool on a batch.
        String vector = embeddings.embedDocument(embeddingTextOf(req));

        TagPreviewResponse predicted = buildPreview(req, vector);

        tx.executeWithoutResult(s -> {
            TagEntity tag = tagRepository.save(TagEntity.builder()
                    .slug(req.getSlug())
                    .name(req.getName())
                    .description(req.getDescription())
                    .examples(req.getExamples())
                    .dim(req.getDim())
                    .source("human")
                    .build());

            tagRepository.writeEmbedding(tag.getId(), vector,
                    "description", embeddings.modelVersion());

            if (req.getDim() != null) {
                candidateRepository.deleteForDim(req.getDim());
                candidateRepository.rebuildForDim(req.getDim(),
                        TagSuggester.CANDIDATES_PER_FACET);
            }

            if (req.getAttachToEventId() != null && !req.getAttachToEventId().isBlank()) {
                eventTagRepository.save(EventTag.builder()
                        .eventId(req.getAttachToEventId())
                        .tagId(tag.getId())
                        .source("human")
                        .approvedAt(Instant.now())
                        .build());
            }
        });

        tagCatalog.invalidate();

        log.info("Tag '{}' created on dim '{}' — {} of {} facets on that dim now match it",
                req.getSlug(), req.getDim(), predicted.getWouldWin(), predicted.getFacetsOnDim());
        return predicted;
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Byte-identical to what {@code TagEmbeddingBackfill} produces, so a tag
     *  embedded here and one re-embedded at boot land on the same vector. */
    private static String embeddingTextOf(NewTagRequest r) {
        return r.getName() + ". " + r.getDescription() + " " + r.getExamples();
    }

    private void validate(NewTagRequest req, boolean forWrite) {
        if (req.getDim() != null && !Taxonomy.isKnownDim(req.getDim())) {
            throw new IllegalArgumentException(
                    "dim '" + req.getDim() + "' is not one of the eight");
        }
        // A tag is matched by comparing it against facets on its own dim. On a
        // dim with no vectors that comparison never happens, so the tag is not
        // weakly matched — it is unreachable, and it fails silently: it embeds,
        // it rebuilds candidate lists, it returns a clean 201, and it is never
        // suggested for anything. Three tags were lost that way before anyone
        // noticed. This used to be guarded by deriving EMBEDDED_DIMS from the
        // Java tag list; with that list gone, the check belongs here.
        if (req.getDim() != null && !Taxonomy.isEmbedded(req.getDim())) {
            throw new IllegalArgumentException(
                    "dim '" + req.getDim() + "' carries no facet vectors, so a tag on it "
                    + "could never be suggested. Embedded dims: " + Taxonomy.EMBEDDED_DIMS
                    + ". Leave dim null for an exclusion-only tag.");
        }
        if (forWrite && tagRepository.findBySlug(req.getSlug()).isPresent()) {
            throw new IllegalArgumentException("slug '" + req.getSlug() + "' already exists");
        }
    }

    private TagPreviewResponse buildPreview(NewTagRequest req, String vector) {
        List<TagPreviewResponse.Claim> claims = new ArrayList<>();
        int onDim = 0;

        if (req.getDim() != null) {
            for (Object[] row : facetRepository.previewAgainstDim(req.getDim(), vector)) {
                onDim++;
                double now  = ((Number) row[2]).doubleValue();
                double best = ((Number) row[3]).doubleValue();
                if (now > best) {
                    claims.add(TagPreviewResponse.Claim.builder()
                            .facetValue((String) row[1])
                            .newScore(now).currentBest(best).build());
                }
            }
        }

        long tagsAfter = req.getDim() == null ? 0
                : tagRepository.findAll().stream()
                        .filter(t -> req.getDim().equals(t.getDim()))
                        .filter(t -> !req.getSlug().equals(t.getSlug()))
                        .count() + 1;

        return TagPreviewResponse.builder()
                .slug(req.getSlug())
                .dim(req.getDim())
                .wouldWin(claims.size())
                .facetsOnDim(onDim)
                .tagsOnDimAfter((int) tagsAfter)
                .singleTagWarning(req.getDim() != null && tagsAfter < 2)
                .claims(claims)
                .build();
    }
}
