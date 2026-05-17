package com.ticketing.ticket.kafka;

import com.ticketing.common.events.*;
import com.ticketing.common.outbox.OutboxAwarePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes ticket-domain events.
 *
 * <h3>Two-tier publish strategy</h3>
 * <ul>
 *   <li><b>{@link #publishReserved}</b> — happy-path event the saga depends on to advance
 *       past the first hop. Routed through {@link OutboxAwarePublisher}: synchronous Kafka
 *       send with hybrid-outbox fallback. If the broker is unreachable, the event is
 *       persisted to {@code outbox_events} and the scheduler retries until success.</li>
 *   <li><b>{@link #publishConfirmed}, {@link #publishReleased},
 *       {@link #publishEventStatusChanged}</b> — fire-and-forget via raw
 *       {@link KafkaTemplate}. If any of these fails, recovery is handled elsewhere:
 *       the saga-orchestrator's stuck-saga watchdog will compensate via
 *       {@code TicketReleaseCommand}, and the ticket-service's own
 *       {@code reservedUntil} watchdog (120 s timeout) releases stranded RESERVED rows
 *       so seats never stay locked permanently.</li>
 * </ul>
 *
 * <p>This intentionally keeps outbox machinery off the failure paths — fewer DB writes
 * under load, simpler code, and the existing watchdogs already provide eventual
 * recovery for any event that gets dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final OutboxAwarePublisher outboxAwarePublisher;

    /**
     * Critical happy-path publish — saga blocks on this event to advance from STARTED
     * to TICKET_RESERVED. Uses synchronous Kafka send with outbox fallback so a brief
     * broker hiccup doesn't strand the saga.
     */
    public void publishReserved(TicketReservedEvent event) {
        outboxAwarePublisher.publish(Topics.TICKET_RESERVED, event.getTicketId(), event);
    }

    /** Fire-and-forget — saga watchdog recovers if lost. */
    public void publishConfirmed(TicketConfirmedEvent event) {
        send(Topics.TICKET_CONFIRMED, event.getTicketId(), event);
    }

    /** Fire-and-forget — saga watchdog and reservedUntil watchdog recover if lost. */
    public void publishReleased(TicketReleasedEvent event) {
        send(Topics.TICKET_RELEASED, event.getTicketId(), event);
    }

    /** Fire-and-forget broadcast for event-lifecycle subscribers. */
    public void publishEventStatusChanged(EventStatusChangedEvent event) {
        send(Topics.EVENT_STATUS_CHANGED, event.getEventId(), event);
    }

    private void send(String topic, String key, DomainEvent event) {
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} to topic={} key={}",
                                event.getClass().getSimpleName(), topic, key, ex);
                    } else {
                        log.debug("Published {} to topic={} key={} offset={}",
                                event.getClass().getSimpleName(), topic, key,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
