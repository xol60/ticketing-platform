package com.ticketing.ticket.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.EventHotnessChangedEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link EventHotnessChangedEvent} on the
 * {@link Topics#EVENT_HOTNESS_CHANGED} topic — only on HOT-flag transitions
 * (not every watchdog tick).
 *
 * <p>Separate from {@code TicketEventPublisher} so the hot-flag signalling
 * never interleaves with saga-flow event publishing (different concerns,
 * different lifecycle). Both share the same {@code KafkaTemplate} bean.
 *
 * <p>Keyed by {@code eventId} so all hotness transitions for one event
 * land on the same partition and are observed in order — important if a
 * consumer reasons about "hot → not-hot → hot" sequences.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventHotnessPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishTransition(String eventId, boolean hot, long viewsPerMinute) {
        EventHotnessChangedEvent payload =
                new EventHotnessChangedEvent(null, eventId, hot, viewsPerMinute);
        kafkaTemplate.send(Topics.EVENT_HOTNESS_CHANGED, eventId, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish hotness transition eventId={} hot={}: {}",
                                eventId, hot, ex.getMessage());
                    } else {
                        log.info("Hotness transition published: eventId={} hot={} views/min={}",
                                eventId, hot, viewsPerMinute);
                    }
                });
    }
}
