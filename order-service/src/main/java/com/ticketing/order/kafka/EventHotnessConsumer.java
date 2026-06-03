package com.ticketing.order.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.EventHotnessChangedEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes hotness transitions published by ticket-service's watchdog.
 *
 * <h3>v1 behaviour: log-only</h3>
 * For the first iteration this consumer is purely an observability hook —
 * it records when an event becomes hot or cools off, but doesn't actively
 * pre-warm caches. The reactive defences (per-pod Caffeine populated by
 * {@code TicketStateConsumer} from {@code ticket.reserved/confirmed/released}
 * + per-ticket LFU eviction) already absorb the dominant flash-sale traffic
 * pattern, and eager pre-fetch would require a new HTTP client to ticket-service.
 *
 * <h3>Future hooks</h3>
 * Two natural extensions, deferred to a later iteration:
 * <ul>
 *   <li>On {@code hot=true}: fetch the event's ticket list and prime
 *       {@code order-ticket-status} so even cold-pod first-reads are fast.
 *   <li>On {@code hot=true}: signal nginx / gateway to bump the per-path rate
 *       limit so legitimate buyers aren't throttled by the surge.
 * </ul>
 *
 * <p>Both will plug in here without changing the producer side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventHotnessConsumer {

    @KafkaListener(topics = Topics.EVENT_HOTNESS_CHANGED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onHotnessChanged(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof EventHotnessChangedEvent event) {
                if (event.isHot()) {
                    log.info("Event went HOT: eventId={} views/min={}",
                            event.getEventId(), event.getViewsPerMinute());
                } else {
                    log.info("Event cooled: eventId={} views/min={}",
                            event.getEventId(), event.getViewsPerMinute());
                }
                // v1: log-only. Future: prime order-ticket-status for this event.
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing EventHotnessChangedEvent: {}", e.getMessage(), e);
            throw e;
        }
    }
}
