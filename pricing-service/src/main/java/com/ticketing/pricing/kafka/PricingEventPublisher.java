package com.ticketing.pricing.kafka;

import com.ticketing.common.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PricingEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishPricingLocked(PricingLockedEvent event) {
        kafkaTemplate.send(Topics.PRICING_LOCKED, event.getTicketId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PricingLockedEvent for ticketId={}: {}",
                                event.getTicketId(), ex.getMessage());
                    } else {
                        log.debug("PricingLockedEvent published: ticketId={}, offset={}",
                                event.getTicketId(), result.getRecordMetadata().offset());
                    }
                });
    }

    public void publishPriceUpdated(PriceUpdatedEvent event) {
        kafkaTemplate.send(Topics.PRICE_UPDATED, event.getEventId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PriceUpdatedEvent for eventId={}: {}",
                                event.getEventId(), ex.getMessage());
                    } else {
                        log.debug("PriceUpdatedEvent published: eventId={}", event.getEventId());
                    }
                });
    }
}
