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
        send(Topics.PRICING_LOCKED, event.getTicketId(), event);
    }

    public void publishPriceUpdated(PriceUpdatedEvent event) {
        send(Topics.PRICE_UPDATED, event.getEventId(), event);
    }

    public void publishPriceChanged(PriceChangedEvent event) {
        send(Topics.PRICING_PRICE_CHANGED, event.getOrderId(), event);
    }

    public void publishPricingFailed(PricingFailedEvent event) {
        send(Topics.PRICING_FAILED, event.getOrderId(), event);
    }

    private void send(String topic, String key, DomainEvent event) {
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} to topic={}: {}",
                                event.getClass().getSimpleName(), topic, ex.getMessage());
                    } else {
                        log.debug("Published {} topic={} offset={}",
                                event.getClass().getSimpleName(), topic,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
