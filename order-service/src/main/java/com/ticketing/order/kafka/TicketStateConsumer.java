package com.ticketing.order.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.TicketConfirmedEvent;
import com.ticketing.common.events.TicketReleasedEvent;
import com.ticketing.common.events.TicketReservedEvent;
import com.ticketing.common.events.Topics;
import com.ticketing.order.service.TicketStatusCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Subscribes to ticket-state events so order-service's local
 * {@link TicketStatusCache} mirrors ticket-service's authoritative state
 * across pods.
 *
 * <h3>Cache transitions</h3>
 * <ul>
 *   <li>{@code ticket.reserved}  → {@link TicketStatusCache#markTaken} +
 *       delete any {@code order-intent:{ticketId}} lock (the saga has now
 *       taken authoritative ownership via {@code ticket:lock:{ticketId}},
 *       so the upstream intent-lock no longer needs to gate other orders).
 *   <li>{@code ticket.confirmed} → {@link TicketStatusCache#markTaken}
 *       (still taken; refresh TTL).
 *   <li>{@code ticket.released}  → {@link TicketStatusCache#markReleased} +
 *       delete the intent-lock for safety (the ticket is now buyable again).
 * </ul>
 *
 * <h3>Why this is a separate consumer (not folded into existing OrderEventConsumer)</h3>
 * Each {@code @KafkaListener} gets its own container + threads. Keeping this
 * separate means a slow ticket-event handler can never starve the saga-event
 * consumer that's already in OrderEventConsumer. They use the same factory
 * (concurrency = 3 per listener), so a stuck thread here loses 33% of THIS
 * listener's throughput only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStateConsumer {

    private static final String INTENT_LOCK_PREFIX = "order-intent:";

    private final TicketStatusCache   ticketStatusCache;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = Topics.TICKET_RESERVED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReserved(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReservedEvent event) {
                ticketStatusCache.markTaken(event.getTicketId());
                // Saga has the authoritative lock now — release the upstream
                // intent-lock so it doesn't linger until its 5s TTL.
                redisTemplate.delete(INTENT_LOCK_PREFIX + event.getTicketId());
                log.debug("Cache: ticketId={} marked TAKEN (reserved)", event.getTicketId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReservedEvent: {}", e.getMessage(), e);
            // Do NOT ack: let Kafka redeliver; markTaken is idempotent.
            throw e;
        }
    }

    @KafkaListener(topics = Topics.TICKET_CONFIRMED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketConfirmedEvent event) {
                ticketStatusCache.markTaken(event.getTicketId());
                log.debug("Cache: ticketId={} marked TAKEN (confirmed)", event.getTicketId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketConfirmedEvent: {}", e.getMessage(), e);
            throw e;
        }
    }

    @KafkaListener(topics = Topics.TICKET_RELEASED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReleased(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReleasedEvent event) {
                ticketStatusCache.markReleased(event.getTicketId());
                // Belt-and-braces: also clear the intent-lock if any saga that
                // briefly held it released without ever progressing to RESERVED.
                redisTemplate.delete(INTENT_LOCK_PREFIX + event.getTicketId());
                log.debug("Cache: ticketId={} marked RELEASED", event.getTicketId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReleasedEvent: {}", e.getMessage(), e);
            throw e;
        }
    }
}
