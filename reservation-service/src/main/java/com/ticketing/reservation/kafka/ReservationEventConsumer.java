package com.ticketing.reservation.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.OrderConfirmedEvent;
import com.ticketing.common.events.TicketReleasedEvent;
import com.ticketing.common.events.Topics;
import com.ticketing.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventConsumer {

    private final ReservationService reservationService;

    @KafkaListener(topics = Topics.TICKET_RELEASED, groupId = "reservation-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReleased(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReleasedEvent event) {
                log.info("Received TICKET_RELEASED ticketId={} reason={}", event.getTicketId(), event.getReason());
                reservationService.promoteNextFromQueue(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TICKET_RELEASED key={}", record.key(), e);
            ack.acknowledge();
        }
    }

    /**
     * Listens to {@code order.confirmed} to mark the promoted user's reservation
     * as PURCHASED and eagerly release the Redis exclusive hold.
     *
     * <p>Without this, the stale-promotion watchdog would fire at
     * {@code promoteExpiresAt} (up to 10 minutes later), expire the reservation,
     * and spuriously promote the next user in queue — causing a failed order for
     * someone who never had a chance to buy.
     */
    @KafkaListener(topics = Topics.ORDER_CONFIRMED, groupId = "reservation-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderConfirmedEvent event) {
                log.info("Received ORDER_CONFIRMED orderId={} userId={} ticketId={}",
                        event.getOrderId(), event.getUserId(), event.getTicketId());
                reservationService.onOrderConfirmed(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing ORDER_CONFIRMED key={}", record.key(), e);
            ack.acknowledge();
        }
    }
}
