package com.ticketing.order.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.OrderCreatedEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishOrderCreated(String traceId, String sagaId,
                                    String orderId, String userId,
                                    String ticketId, BigDecimal requestedPrice) {
        var event = new OrderCreatedEvent(traceId, sagaId, orderId, userId, ticketId, requestedPrice);
        kafkaTemplate.send(Topics.ORDER_CREATED, orderId, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish OrderCreatedEvent orderId={} sagaId={}: {}",
                                orderId, sagaId, ex.getMessage(), ex);
                    } else {
                        log.info("Published OrderCreatedEvent orderId={} sagaId={} partition={} offset={}",
                                orderId, sagaId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
