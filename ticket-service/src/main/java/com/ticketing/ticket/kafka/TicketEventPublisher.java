package com.ticketing.ticket.kafka;

import com.ticketing.common.events.*;
import com.ticketing.common.events.EventStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishReserved(TicketReservedEvent event) {
        send(Topics.TICKET_RESERVED, event.getTicketId(), event);
    }

    public void publishConfirmed(TicketConfirmedEvent event) {
        send(Topics.TICKET_CONFIRMED, event.getTicketId(), event);
    }

    public void publishReleased(TicketReleasedEvent event) {
        send(Topics.TICKET_RELEASED, event.getTicketId(), event);
    }

    public void publishEventStatusChanged(EventStatusChangedEvent event) {
        send(Topics.EVENT_STATUS_CHANGED, event.getEventId(), event);
    }

    private void send(String topic, String key, DomainEvent event) {
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} to topic={} key={}", event.getClass().getSimpleName(), topic, key, ex);
                    } else {
                        log.debug("Published {} to topic={} key={} offset={}",
                                event.getClass().getSimpleName(), topic, key,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
