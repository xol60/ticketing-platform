package com.ticketing.agent.curation;

import com.ticketing.agent.domain.model.EventTag;
import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.config.AgentProperties;
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
    private final AgentProperties             properties;
    private final TransactionTemplate         tx;

    /** Shown before anything is written. See {@link TagPreviewResponse}. */
    public TagPreviewResponse preview(NewTagRequest req) {
        validate(req, false);
        // embedDocument, not embedQuery: a tag is stored content, and the two
        // apply different prefixes. With both prefixes empty today the vectors
        // coincide, so a preview that used the query side agreed with the
        // create by luck; setting a prefix would have made the preview quietly
        // predict a tag other than the one written.
        String vector = embeddings.embedDocument(embeddingTextOf(req));   // outside any transaction
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

        // Refused, not thrown. The reviewer needs the overlap in front of them
        // to choose between the two ways out — insist on the distinction, or
        // use the tag that already exists — and an error body carrying only a
        // message would take that away.
        if (predicted.isDuplicateWarning() && !req.isAcknowledgeDuplicate()) {
            log.info("Tag '{}' refused: displaces {}", req.getSlug(),
                    predicted.getOverlaps().stream()
                            .filter(TagPreviewResponse.Overlap::isDisplaced)
                            .map(TagPreviewResponse.Overlap::getSlug).toList());
            return predicted;
        }

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

            // Every facet a rebuild hands to a new rank-one tag becomes a
            // proposal waiting for review. Without it the reviewer sees their
            // tag attached to the one event they named and nothing else until
            // the whole corpus is re-ingested.
            if (req.getDim() != null) rebuildEveryDim();

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
        predicted.setCreated(true);
        return predicted;
    }

    /**
     * The other way out of a duplicate warning: use the tag that already exists.
     *
     * <p>A reviewer who decides the candidate was a rewording of something in
     * the vocabulary still has a facet in front of them that nothing is
     * assigned to. This attaches the existing tag to that facet's event and
     * approves it, which is the outcome writing a near-duplicate would have
     * produced without the second vector competing for the same facets forever
     * after.
     *
     * <p>Approves a pending machine proposal in place when the matcher already
     * suggested this pair, rather than inserting beside it — the primary key is
     * (event, tag), and the reviewer is answering exactly the question that row
     * was waiting on.
     */
    public void attachExisting(String eventId, String slug) {
        verdict(eventId, slug, true);
    }

    /**
     * The other half of a review: this tag does not belong on this event.
     *
     * <p>Recorded, not deleted. The row is the rejection — it is what stops the
     * matcher proposing the same pair again on the next ingest, which is
     * exactly what happened before {@code rejected_at} existed: a reviewer's
     * "no" was stored by removing the row, and the deterministic matcher
     * regenerated it word for word an hour later.
     */
    public void rejectProposal(String eventId, String slug) {
        verdict(eventId, slug, false);
    }

    private void verdict(String eventId, String slug, boolean approve) {
        TagEntity tag = tagRepository.findBySlug(slug).orElseThrow(
                () -> new IllegalArgumentException("no tag with slug '" + slug + "'"));

        tx.executeWithoutResult(s -> {
            EventTag row = eventTagRepository
                    .findById(new EventTag.Key(eventId, tag.getId()))
                    .orElseGet(() -> EventTag.builder()
                            .eventId(eventId).tagId(tag.getId())
                            .source("human")
                            .build());
            // One verdict at a time — a CHECK constraint enforces it — so
            // changing a reviewer's mind has to clear the other side first.
            row.setApprovedAt(approve ? Instant.now() : null);
            row.setRejectedAt(approve ? null : Instant.now());
            eventTagRepository.save(row);
        });

        log.info("Tag '{}' {} on event {} by review", slug,
                approve ? "approved" : "rejected", eventId);
    }

    /**
     * Rewrites an existing tag's definition and re-embeds it.
     *
     * <p>The move a reviewer needs when a tag is right but its wording is
     * wrong, and the last hole in this flow — until now the only way to change
     * a definition was to delete the row and lose every verdict attached to it.
     *
     * <p>Editing is not cosmetic. The definition <em>is</em> the vector, so
     * changing the text changes which facets the tag wins, on its own dim and
     * against every other. The case that forced this: {@code conference-keynote}
     * was written with "attended to learn and to network" and examples naming
     * developer conferences — audience language inside a format tag — and it
     * then out-scored {@code technical-practitioners} on
     * {@code "developers, engineers, and technology enthusiasts"} by 0.671 to
     * 0.602, taking an audience facet off the audience dim entirely.
     *
     * <p>Runs the same rebuild as creation, for the same reason: stale
     * candidate lists were scored against text that no longer exists, and a
     * reviewer who sees an unchanged screen concludes the edit did nothing.
     * Verdicts survive — a person's answer about this tag on this event is not
     * invalidated by better wording.
     *
     * @return the preview recomputed after the write
     */
    public TagPreviewResponse edit(String slug, NewTagRequest req) {
        TagEntity tag = tagRepository.findBySlug(slug).orElseThrow(
                () -> new IllegalArgumentException("no tag with slug '" + slug + "'"));

        // The slug and dim are identity here, not content. Moving a tag to
        // another dim would strand every verdict it carries against facets it
        // can no longer be compared to, so that is a new tag, not an edit.
        req.setSlug(slug);
        req.setDim(tag.getDim());
        validate(req, false);

        String vector = embeddings.embedDocument(embeddingTextOf(req));
        TagPreviewResponse predicted = buildPreview(req, vector);

        tx.executeWithoutResult(s -> {
            tag.setName(req.getName());
            tag.setDescription(req.getDescription());
            tag.setExamples(req.getExamples());
            tagRepository.save(tag);
            tagRepository.writeEmbedding(tag.getId(), vector,
                    "description", embeddings.modelVersion());

            if (tag.getDim() != null) rebuildEveryDim();
        });

        tagCatalog.invalidate();
        log.info("Tag '{}' redefined — now top match for {} of {} facets on '{}'",
                slug, predicted.getWouldWin(), predicted.getFacetsOnDim(), tag.getDim());
        predicted.setCreated(true);
        return predicted;
    }

    /**
     * Rebuilds candidate lists on every embedded dim, not just the tag's own.
     *
     * <p>Since candidates are chosen by comparing a facet's own dim against
     * every other dim, one tag's wording decides outcomes on dims it does not
     * live on. Rebuilding only the tag's dim leaves the rest stale, and the
     * staleness is invisible — the numbers look settled and are simply wrong.
     *
     * <p>Measured: rewriting {@code conference-keynote} to drop the audience
     * language from a format tag handed three audience facets back to
     * {@code technical-practitioners} and {@code business-investors}, including
     * "developers, engineers, and technology enthusiasts" which the format tag
     * had been taking at 0.671 to 0.602. None of that reached
     * {@code facet_tag_candidate} until the audience dim was rebuilt too.
     *
     * <p>Cheap enough to do unconditionally: the whole thing is one INSERT per
     * dim inside Postgres, over a few hundred rows, and it runs when a person
     * writes a definition rather than on any hot path.
     */
    private void rebuildEveryDim() {
        for (String d : Taxonomy.EMBEDDED_DIMS) {
            candidateRepository.deleteForDim(d);
            candidateRepository.rebuildForDim(d, TagSuggester.CANDIDATES_PER_FACET);
            candidateRepository.clearUnansweredForDim(d);
            candidateRepository.proposeRankOneForDim(d);
        }
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

        List<TagPreviewResponse.Overlap> overlaps = overlapsFor(req, vector);

        return TagPreviewResponse.builder()
                .slug(req.getSlug())
                .dim(req.getDim())
                .wouldWin(claims.size())
                .facetsOnDim(onDim)
                .tagsOnDimAfter((int) tagsAfter)
                .singleTagWarning(req.getDim() != null && tagsAfter < 2)
                .claims(claims)
                .overlaps(overlaps)
                .duplicateWarning(overlaps.stream().anyMatch(TagPreviewResponse.Overlap::isDisplaced))
                .similarityBaseline(req.getDim() == null ? null
                        : tagRepository.closestExistingPair(req.getDim()))
                .created(false)
                .build();
    }

    /**
     * Minimum facets a tag must hold before displacement means anything.
     *
     * <p>Below this the ratio says nothing: a tag holding one facet is
     * "completely displaced" by any candidate that outscores it once, and that
     * is an ordinary reshuffle rather than a duplicate. Three is where a
     * majority stops being a coin flip.
     */
    private static final int DISPLACEMENT_FLOOR = 3;

    /**
     * Two independent ways a candidate can be a duplicate, and it only takes one.
     *
     * <p><b>It takes over.</b> Half or more of what an existing tag holds. From
     * the outside that is what a rewrite of that tag looks like, whatever the
     * definitions say.
     *
     * <p><b>It reads the same.</b> Closer to an existing tag than any two
     * existing tags on the dim are to each other, <em>and</em> taking at least
     * one of that tag's facets. No constant: the bar is the vocabulary's own
     * closest pair, so it moves as the vocabulary grows and survives a change
     * of embedding model. It is silent until the dim holds three tags, because
     * a single pair is not a distribution.
     *
     * <p>The {@code taken > 0} clause is what stops wording alone from
     * convicting. {@code formal-ceremonial} scored 0.721 against
     * {@code focused-and-technical} while taking none of its facets — two
     * definitions about a quiet room that nonetheless sort the corpus into
     * different halves, which is what a useful distinction looks like.
     *
     * <p>Both are needed because they miss different things, which is not a
     * guess — it was measured on the first version of this check, which had
     * only the first test. A candidate written as an outright restatement of
     * {@code performing-arts} scored 0.883 against it while the dim's closest
     * genuine pair was 0.713, and it took 17 of 40 facets: a plain duplicate by
     * wording, and under the displacement bar. The reverse case is
     * {@code broadcast} against {@code large-scale} — 0.675, comfortably
     * ordinary, and it took most of the stadium facets anyway.
     */
    private static boolean duplicates(int held, int taken, double similarity, Double baseline) {
        boolean takesOver  = held >= DISPLACEMENT_FLOOR && taken * 2 >= held;
        boolean readsAlike = baseline != null && similarity > baseline && taken > 0;
        return takesOver || readsAlike;
    }

    private List<TagPreviewResponse.Overlap> overlapsFor(NewTagRequest req, String vector) {
        if (req.getDim() == null) return List.of();

        // Null on a dim holding fewer than two tags — there is no pair to
        // measure, so wording cannot be judged and displacement decides alone.
        Double baseline = tagRepository.closestExistingPair(req.getDim());

        List<TagPreviewResponse.Overlap> out = new ArrayList<>();
        for (Object[] row : facetRepository.overlapAgainstDim(req.getDim(), vector)) {
            String slug = (String) row[0];
            if (slug.equals(req.getSlug())) continue;   // re-previewing an existing slug
            int    held       = ((Number) row[2]).intValue();
            int    taken      = ((Number) row[3]).intValue();
            double similarity = ((Number) row[4]).doubleValue();
            out.add(TagPreviewResponse.Overlap.builder()
                    .slug(slug)
                    .name((String) row[1])
                    .held(held)
                    .taken(taken)
                    .textSimilarity(similarity)
                    .displaced(duplicates(held, taken, similarity, baseline))
                    .build());
        }
        return out;
    }
}
