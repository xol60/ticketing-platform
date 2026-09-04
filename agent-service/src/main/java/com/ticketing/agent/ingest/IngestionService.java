package com.ticketing.agent.ingest;

import com.ticketing.agent.domain.model.*;
import com.ticketing.agent.domain.repository.*;
import com.ticketing.agent.llm.ExtractionResult;
import com.ticketing.agent.llm.IngestionExtractor;
import com.ticketing.agent.validation.FacetValidator;
import com.ticketing.agent.validation.ValidationOutcome;
import com.ticketing.agent.vector.DimGate;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.vector.TagMatcher;
import com.ticketing.agent.config.AgentProperties;
import com.ticketing.common.agent.Taxonomy;
import com.ticketing.common.events.EventSearchIndexedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Turns one {@code event.search.indexed} message into a reviewed event.
 *
 * <h3>Shape of the flow</h3>
 * <pre>
 *   upsert the projection      (fast, no model)
 *     → extract                (one LLM call, seconds)
 *     → validate               (deterministic gates, no I/O)
 *     → embed + persist        (one embedding call per surviving facet)
 * </pre>
 *
 * <h3>The projection is written before extraction, on purpose</h3>
 * An event whose extraction fails — Ollama down, model missing, output
 * unparseable after retries — still exists in {@code agent_event}, unreviewed
 * and not searchable. That makes the failure visible in the review queue
 * instead of leaving a gap nobody can see, and it means a later re-ingest has
 * a row to update rather than needing to recreate one.
 *
 * <h3>Machine output is replaced, human output is not</h3>
 * Re-ingest deletes and rewrites {@code llm} rows and leaves {@code human}
 * rows alone. Without that split, editing an event's description upstream
 * would silently discard every correction a reviewer had made.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final AgentEventRepository     eventRepository;
    private final EventFacetRepository     facetRepository;
    private final EventTagRepository       eventTagRepository;
    private final TagRepository            tagRepository;
    private final FacetRejectionRepository rejectionRepository;

    private final CityResolver       cityResolver;
    private final IngestionExtractor extractor;
    private final FacetValidator     validator;
    private final EmbeddingService   embeddings;
    private final DimGate            dimGate;
    private final TagMatcher           tagMatcher;
    private final TagSuggester         tagSuggester;
    private final AgentProperties    properties;

    /**
     * Programmatic transactions, not {@code @Transactional}.
     *
     * <p>Two reasons, and the first is a correctness one: the write steps are
     * called from {@link #ingest} inside this same bean, and Spring's proxy
     * cannot intercept a self-invocation — annotations on those methods would
     * be silently inert, which is the worst kind of wrong because nothing
     * fails and every write just runs without a transaction.
     *
     * <p>The second is shape. Ingestion interleaves database writes with calls
     * that take seconds; a transaction spanning the whole method would pin a
     * pooled connection for the length of an inference run. Short explicit
     * boundaries keep the slow parts outside them.
     */
    private final TransactionTemplate tx;

    /**
     * Crowd-size bands, from ticket count.
     *
     * <p>Derived rather than described, which is what lets the contradiction
     * gate treat a mismatch as fact against claim. The boundaries follow the
     * taxonomy's own attribute tags: {@code intimate} is under 300 seats,
     * {@code large-scale} is over 2000.
     */
    private static String capacityBand(Integer ticketCount) {
        if (ticketCount == null || ticketCount == 0) return null;
        if (ticketCount < 300)  return "small";
        if (ticketCount <= 2000) return "medium";
        return "large";
    }

    /**
     * Ingests one event.
     *
     * <p>Not transactional as a whole. The LLM and embedding calls take
     * seconds to a minute, and holding a database transaction open across them
     * would pin a connection for the entire batch. Each write step manages its
     * own boundary instead; a crash between them leaves the event unreviewed,
     * which the next redelivery corrects.
     */
    public void ingest(EventSearchIndexedEvent message) {
        // Read before the upsert overwrites it. Both facts are needed: whether
        // this event has been seen at all, and what its description said last
        // time — the two together are what distinguish a genuine edit from the
        // same message arriving again.
        var existing = eventRepository.findById(message.getEventId());
        boolean seenBefore = existing.isPresent();
        String previousDescription = existing.map(AgentEvent::getDescriptionRaw).orElse(null);
        String previousPrompt = existing.map(AgentEvent::getPromptVersion).orElse(null);

        AgentEvent event = upsertProjection(message);

        // Non-OPEN events are projected but never extracted. Spending a minute
        // of inference on a cancelled event is waste, and its facets could
        // never surface anyway.
        if (!"OPEN".equalsIgnoreCase(message.getStatus())) {
            log.debug("Event {} is {} — projection updated, extraction skipped",
                    event.getId(), message.getStatus());
            return;
        }

        // ticket-service republishes on every metadata edit and every status
        // change, so the same description arrives many times — 196 messages
        // for 92 events in the current topic. Extraction is deterministic
        // (temperature 0), so re-running it on unchanged text would spend a
        // minute of inference to produce exactly what is already stored, and
        // would churn the review queue by deleting and recreating rows a human
        // may already have looked at.
        // The description is not the only input. A prompt edit changes what the
        // model was asked to do, and skipping on description alone left the
        // corpus holding facets produced by instructions that no longer exist.
        if (seenBefore
                && java.util.Objects.equals(previousDescription, event.getDescriptionRaw())
                && IngestionExtractor.PROMPT_VERSION.equals(previousPrompt)) {
            log.debug("Event {} unchanged and extracted by the current prompt — reusing facets",
                    event.getId());
            return;
        }

        extract(event);
    }

    /**
     * Runs extraction over an event already in the projection.
     *
     * <p>Split out because extraction depends on exactly two things — the
     * description and the prompt — and the projection holds the first while the
     * code holds the second. Neither needs Kafka. That matters when the prompt
     * changes: the topic's retention had expired by the time the first prompt
     * fix landed, so replay was not available and the fix had no way to reach
     * the 92 events already stored.
     *
     * <p>Called by the consumer for new or edited events, and by the reindex
     * endpoint for a prompt change.
     */
    public void extract(AgentEvent event) {
        ExtractionResult extracted = extractor.extract(event);
        if (extracted.facets().isEmpty()) {
            log.info("Event {} yielded nothing to review", event.getId());
            return;
        }

        List<ValidationOutcome> outcomes = validator.validate(event, extracted.facets());
        persistFacets(event, outcomes);

        event.setPromptVersion(IngestionExtractor.PROMPT_VERSION);
        tx.executeWithoutResult(s -> eventRepository.save(event));

        log.info("Ingested event {} — {} facets kept, {} rejected",
                event.getId(), event.getFacetsKept(), event.getFacetsRejected());
    }

    /**
     * Re-extracts every OPEN event whose facets came from an older prompt.
     *
     * @return how many events were re-extracted
     */
    public int reextractStalePrompts() {
        List<AgentEvent> stale = eventRepository.findAll().stream()
                .filter(e -> "OPEN".equalsIgnoreCase(e.getStatus()))
                .filter(e -> e.getDescriptionRaw() != null && !e.getDescriptionRaw().isBlank())
                .filter(e -> !IngestionExtractor.PROMPT_VERSION.equals(e.getPromptVersion()))
                .toList();

        log.info("Re-extracting {} event(s) whose facets predate prompt {}",
                stale.size(), IngestionExtractor.PROMPT_VERSION);
        int done = 0;
        for (AgentEvent e : stale) {
            try {
                extract(e);
                done++;
            } catch (Exception ex) {
                log.error("Re-extraction failed for {}: {}", e.getId(), ex.getMessage());
            }
        }
        return done;
    }

    private AgentEvent upsertProjection(EventSearchIndexedEvent m) {
        return tx.execute(status -> doUpsertProjection(m));
    }

    private AgentEvent doUpsertProjection(EventSearchIndexedEvent m) {
        AgentEvent event = eventRepository.findById(m.getEventId())
                .orElseGet(() -> AgentEvent.builder().id(m.getEventId()).build());

        event.setName(m.getName());
        event.setPrimaryArtist(m.getPrimaryArtist());
        event.setVenueName(m.getVenueName());
        event.setVenueCity(m.getVenueCity());
        event.setCityId(cityResolver.resolve(m.getVenueCity()));
        event.setCategory(m.getCategory());
        event.setGenre(m.getGenre());
        event.setStatus(m.getStatus());
        event.setStartAt(m.getEventDate());
        event.setSalesOpenAt(m.getSalesOpenAt());
        event.setSalesCloseAt(m.getSalesCloseAt());
        event.setPriceMin(m.getPriceMin());
        event.setPriceMax(m.getPriceMax());
        event.setCapacityBand(capacityBand(m.getTicketCount()));

        // Long copy over short: the long form carries the atmosphere and
        // format detail worth distilling, and a short blurb rarely supports a
        // span long enough to clear the grounding gate.
        event.setDescriptionRaw(m.getFullDescription() != null && !m.getFullDescription().isBlank()
                ? m.getFullDescription()
                : m.getShortDescription());

        event.setIngestedAt(Instant.now());
        return eventRepository.save(event);
    }

    private void persistFacets(AgentEvent event, List<ValidationOutcome> outcomes) {
        tx.executeWithoutResult(status -> {
            facetRepository.deleteLlmFacets(event.getId());
            eventTagRepository.deleteLlmTags(event.getId());
            outcomes.stream().filter(ValidationOutcome::rejected)
                    .forEach(o -> rejectionRepository.save(FacetRejection.builder()
                            .eventId(event.getId())
                            .dim(o.candidate().dim())
                            .value(o.candidate().value())
                            .sourceSpan(o.candidate().span())
                            .reason(o.reason().name())
                            .detail(o.detail())
                            .modelVersion(embeddings.modelVersion())
                            .build()));
        });

        int kept = 0;
        int rejected = (int) outcomes.stream().filter(ValidationOutcome::rejected).count();

        for (ValidationOutcome outcome : outcomes) {
            if (outcome.rejected()) continue;
            var candidate = outcome.candidate();

            // Re-extraction is deterministic, so an approved facet is usually
            // proposed again word for word. Inserting it would duplicate a row
            // a reviewer already ruled on.
            if (facetRepository.existsByEventIdAndDimAndValueAndApprovedAtIsNotNull(
                    event.getId(), candidate.dim(), candidate.value())) {
                kept++;
                continue;
            }

            EventFacet facet = tx.execute(s -> facetRepository.save(EventFacet.builder()
                    .eventId(event.getId())
                    .dim(candidate.dim())
                    .value(candidate.value())
                    .sourceSpan(candidate.span())
                    .source("llm")
                    .build()));
            kept++;

            // Only the dims in EMBEDDED_DIMS get a vector; the rest stay text
            // for rendering and the compare projection. That set is the dims
            // users phrase preferences in, plus every dim a tag lives on — a
            // tag can only be matched against facets on its own dim, so a tag
            // on an unembedded dim is unreachable rather than merely weak.
            //
            // A non-embedded facet is approved here rather than skipped. There
            // is nothing left to check on it — the four deterministic gates
            // already passed, and the dim gate is a vector test that cannot
            // apply. Falling through the `continue` without approving left
            // five of the eight dims permanently unapproved, which emptied the
            // differentiator column on every result row.
            if (!Taxonomy.isEmbedded(candidate.dim())) {
                if (properties.getValidation().isAutoApproveOnAllGatesPass()) {
                    facet.setApprovedAt(Instant.now());
                    tx.executeWithoutResult(s -> facetRepository.save(facet));
                }
                continue;
            }

            // Embedded from value AND span together, not value alone. A value
            // is often one or two words — "arena" — with too little signal to
            // place; a span is raw source text thick with specifics that pull
            // the vector toward the particular. Measured on real facets, each
            // alone mis-matched at least one case and the pair matched all of
            // them. One embedding now serves both the dim gate and tag
            // matching, so this costs nothing extra.
            //
            // Outside any transaction — a network call taking hundreds of
            // milliseconds, and holding a connection across it is how a pool
            // gets exhausted by a batch job.
            String vector = embeddings.embedDocument(
                    TagMatcher.representationOf(candidate.value(), candidate.span()));

            boolean approve = properties.getValidation().isAutoApproveOnAllGatesPass()
                    && dimGate.looksLikeDim(candidate.dim(), vector);

            // Top-N, not top-1. The review has to be able to answer "which of
            // these?" and "none of these?", and neither question exists when
            // only the winner is kept.
            var tagCandidates = tagMatcher.candidatesFor(
                    candidate.dim(), vector, TagSuggester.CANDIDATES_PER_FACET);

            tx.executeWithoutResult(s -> {
                facetRepository.writeEmbedding(facet.getId(), vector, embeddings.modelVersion());
                if (approve) {
                    facet.setApprovedAt(Instant.now());
                    facetRepository.save(facet);
                }
                tagSuggester.record(event.getId(), facet.getId(), tagCandidates);
            });
        }

        event.setFacetsKept(kept);
        event.setFacetsRejected(rejected);

        // searchable is a curation gate, not a business one: an OPEN event with
        // nothing usable stays invisible to the agent. Two is the floor because
        // a single facet cannot distinguish an event from any other on the same
        // dim, so it adds nothing a hard filter would not already do.
        event.setSearchable(kept >= 2);
        tx.executeWithoutResult(s -> eventRepository.save(event));
    }

    /**
     * Writes one tag suggestion, keeping the strongest when several facets on
     * the same dim point at the same tag.
     *
     * <p>An event with three {@code format} facets can suggest
     * {@code live-music} three times. The row is one per (event, tag), so the
     * later ones would either fail on the primary key or overwrite a better
     * score with a worse one.
     */

    /**
     * Records the labels the model volunteered, without assigning any of them.
     *
     * <p>Tags now come from facets, which carry evidence. The model's own tag
     * list has none — it is an assertion with nothing behind it — so it is kept
     * only as a signal for growing the vocabulary: a label that keeps appearing
     * and matches nothing in the catalogue is a gap worth a person's attention.
     */
}
