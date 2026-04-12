package com.ticketing.reservation.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.ReservationPromotedEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishPromoted(ReservationPromotedEvent event) {
        kafkaTemplate.send(Topics.RESERVATION_PROMOTED, event.getTicketId(), event)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ReservationPromotedEvent ticketId={}", event.getTicketId(), ex);
                    } else {
                        log.debug("Published ReservationPromotedEvent ticketId={} userId={}", event.getTicketId(), event.getUserId());
                    }
                });
    }
}
