package com.ticketing.agent.ingest;

import com.ticketing.agent.domain.model.*;
import com.ticketing.agent.domain.repository.*;
import com.ticketing.agent.llm.ExtractionResult;
import com.ticketing.agent.llm.IngestionExtractor;
import com.ticketing.agent.validation.FacetValidator;
import com.ticketing.agent.validation.ValidationOutcome;
import com.ticketing.agent.vector.DimGate;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.vector.TagSnapper;
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
    private final TagSnapper         tagSnapper;
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
        if (seenBefore
                && java.util.Objects.equals(previousDescription, event.getDescriptionRaw())) {
            log.debug("Event {} description unchanged — reusing existing facets", event.getId());
            return;
        }

        ExtractionResult extracted = extractor.extract(event);
        if (extracted.facets().isEmpty() && extracted.tags().isEmpty()) {
            log.info("Event {} yielded nothing to review", event.getId());
            return;
        }

        List<ValidationOutcome> outcomes = validator.validate(event, extracted.facets());
        persistFacets(event, outcomes);
        persistTags(event, extracted.tags());

        log.info("Ingested event {} — {} facets kept, {} rejected, {} tags",
                event.getId(), event.getFacetsKept(), event.getFacetsRejected(),
                extracted.tags().size());
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

            EventFacet facet = tx.execute(s -> facetRepository.save(EventFacet.builder()
                    .eventId(event.getId())
                    .dim(candidate.dim())
                    .value(candidate.value())
                    .sourceSpan(candidate.span())
                    .source("llm")
                    .build()));
            kept++;

            // Only the three query-facing dims get a vector; the rest stay text
            // for rendering and the compare projection. Embedding all eight
            // would nearly triple the cost for dims nothing compares against.
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

            // Outside any transaction — this is a network call taking hundreds
            // of milliseconds, and holding a connection across it is how a
            // pool gets exhausted by a batch job.
            String vector = embeddings.embedDocument(candidate.value());

            boolean approve = properties.getValidation().isAutoApproveOnAllGatesPass()
                    && dimGate.looksLikeDim(candidate.dim(), vector);

            tx.executeWithoutResult(s -> {
                facetRepository.writeEmbedding(facet.getId(), vector, embeddings.modelVersion());
                if (approve) {
                    facet.setApprovedAt(Instant.now());
                    facetRepository.save(facet);
                }
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

    private void persistTags(AgentEvent event, List<String> labels) {
        tx.executeWithoutResult(s -> eventTagRepository.deleteLlmTags(event.getId()));

        for (String label : labels) {
            // Snapping embeds the label, so it happens outside a transaction
            // for the same reason facet embedding does.
            var slug = tagSnapper.snap(label, event.getId());
            slug.flatMap(tagRepository::findBySlug)
                .ifPresent(tag -> tx.executeWithoutResult(s ->
                        eventTagRepository.save(EventTag.builder()
                                .eventId(event.getId())
                                .tagId(tag.getId())
                                .source("llm")
                                .build())));
        }
    }
}
