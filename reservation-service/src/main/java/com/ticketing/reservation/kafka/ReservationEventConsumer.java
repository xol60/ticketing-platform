package com.ticketing.reservation.kafka;

import com.ticketing.common.events.DomainEvent;
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
}
