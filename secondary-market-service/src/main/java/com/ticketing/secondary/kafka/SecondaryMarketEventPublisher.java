package com.ticketing.secondary.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.OrderCreatedEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecondaryMarketEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        kafkaTemplate.send(Topics.ORDER_CREATED, event.getOrderId(), event)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish OrderCreatedEvent orderId={}", event.getOrderId(), ex);
                    } else {
                        log.info("Published OrderCreatedEvent for secondary purchase orderId={}", event.getOrderId());
                    }
                });
    }
}
