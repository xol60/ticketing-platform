package com.ticketing.order.kafka;

import com.ticketing.common.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishOrderCreated(String traceId, String sagaId,
                                    String orderId, String userId,
                                    String ticketId, BigDecimal userPrice,
                                    Instant orderCreatedAt) {
        var event = new OrderCreatedEvent(traceId, sagaId, orderId, userId,
                ticketId, userPrice, orderCreatedAt);
        send(Topics.ORDER_CREATED, orderId, event);
    }

    public void publishPriceConfirm(String traceId, String sagaId,
                                    String orderId, String userId) {
        var cmd = new OrderPriceConfirmCommand(traceId, sagaId, orderId, userId);
        send(Topics.ORDER_PRICE_CONFIRM, orderId, cmd);
    }

    public void publishPriceCancel(String traceId, String sagaId,
                                   String orderId, String userId) {
        var cmd = new OrderPriceCancelCommand(traceId, sagaId, orderId, userId);
        send(Topics.ORDER_PRICE_CANCEL, orderId, cmd);
    }

    private void send(String topic, String key, DomainEvent event) {
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} to topic={}: {}",
                                event.getClass().getSimpleName(), topic, ex.getMessage());
                    } else {
                        log.info("Published {} topic={} key={} offset={}",
                                event.getClass().getSimpleName(), topic, key,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
