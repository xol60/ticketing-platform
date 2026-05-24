package com.ticketing.search.kafka;

import com.ticketing.common.events.EventSearchIndexedEvent;
import com.ticketing.common.events.EventStatus;
import com.ticketing.common.events.Topics;
import com.ticketing.search.config.CacheConfig;
import com.ticketing.search.domain.model.EventDocument;
import com.ticketing.search.domain.repository.EventSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code event.search.indexed} and projects the payload into ES.
 *
 * <h3>OPEN → upsert, anything else → delete</h3>
 * The public search surface should only return events a user can actually buy
 * a ticket for — so {@code OPEN} events are the only ones that belong in the
 * index. Any other status (DRAFT, SALES_CLOSED, CANCELLED, COMPLETED) means
 * the event must not appear in search results, so we delete the document.
 * Deleting an absent document is a no-op in ES, so this stays safe across
 * unordered redelivery within a partition's boundary.
 *
 * <h3>Why this is safe under Kafka at-least-once delivery</h3>
 * <ul>
 *   <li>{@code repository.save(doc)} is a {@code PUT _doc/{id}} on the ES
 *       wire — fully idempotent. Replaying the same indexing event N times
 *       converges to the same final document.</li>
 *   <li>{@code repository.deleteById(id)} of an already-deleted id is a no-op
 *       in ES.</li>
 *   <li>The producer keys by {@code eventId}, so all updates for one event
 *       land on the same partition and are applied in producer order.</li>
 *   <li>We {@code ack()} only AFTER the ES call succeeds. If ES is down, the
 *       message is redelivered; if our pod crashes mid-call, the message is
 *       redelivered. Either way the document eventually catches up.</li>
 * </ul>
 *
 * <h3>Failure handling</h3>
 * Any exception escaping this listener triggers Spring-Kafka's default
 * back-off + retry. We deliberately do NOT swallow exceptions — losing an
 * index update would silently corrupt search results until the next status
 * change for that event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventIndexConsumer {

    private final EventSearchRepository repository;
    /** Used to invalidate the autocomplete cache after every successful ES write
     *  so consumers see edits within Kafka latency, not after the 60 s TTL. */
    private final CacheManager cacheManager;

    @KafkaListener(
            topics    = Topics.EVENT_SEARCH_INDEXED,
            groupId   = "search-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEvent(EventSearchIndexedEvent event, Acknowledgment ack) {
        if (event == null || event.getEventId() == null) {
            log.warn("Skipping malformed EventSearchIndexedEvent (null payload or eventId)");
            ack.acknowledge();
            return;
        }

        try {
            if (isOpen(event.getStatus())) {
                EventDocument doc = toDocument(event);
                repository.save(doc);
                log.info("Indexed event id={} name={} status={}",
                        event.getEventId(), event.getName(), event.getStatus());
            } else {
                repository.deleteById(event.getEventId());
                log.info("Removed event id={} status={} from search index",
                        event.getEventId(), event.getStatus());
            }

            // Any event change can affect what /suggest returns — a new OPEN
            // event must appear in its prefix's results, a CANCELLED event
            // must disappear from results that previously matched it. We
            // can't enumerate which prefixes a given event hits without
            // re-running the analyzer, so we invalidate the whole region.
            // Suggest cache entries are cheap (~1 KB) and the working set
            // rebuilds within seconds of live traffic, so this is fine.
            // Done AFTER the ES write so a failed write doesn't blow the
            // cache for nothing (we'd just refill with the same stale data).
            invalidateSuggestCache();

            ack.acknowledge();
        } catch (Exception e) {
            // Do NOT ack — let Spring-Kafka redeliver. Losing an index update
            // silently corrupts search until the next status change.
            log.error("Failed to apply index update for event id={}: {}",
                    event.getEventId(), e.getMessage(), e);
            throw e;
        }
    }

    private void invalidateSuggestCache() {
        Cache suggest = cacheManager.getCache(CacheConfig.SUGGEST_REGION);
        if (suggest != null) {
            suggest.invalidate();
        }
    }

    /** Status string comparison kept in one place so future enum changes only update here. */
    private static boolean isOpen(String status) {
        return EventStatus.OPEN.name().equalsIgnoreCase(status);
    }

    private static EventDocument toDocument(EventSearchIndexedEvent e) {
        return EventDocument.builder()
                .id(e.getEventId())
                .name(e.getName())
                .primaryArtist(e.getPrimaryArtist())
                .venueName(e.getVenueName())
                .venueCity(e.getVenueCity())
                .shortDescription(e.getShortDescription())
                .fullDescription(e.getFullDescription())
                .category(e.getCategory())
                .genre(e.getGenre())
                .status(e.getStatus())
                .eventDate(e.getEventDate())
                .salesOpenAt(e.getSalesOpenAt())
                .salesCloseAt(e.getSalesCloseAt())
                .build();
    }
}
