package com.ticketing.order.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.OrderConfirmedEvent;
import com.ticketing.common.events.OrderFailedEvent;
import com.ticketing.common.events.Topics;
import com.ticketing.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderService orderService;

    @KafkaListener(
            topics = Topics.ORDER_CONFIRMED,
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderConfirmedEvent event) {
                log.info("Received OrderConfirmedEvent orderId={} sagaId={} traceId={}",
                        event.getOrderId(), event.getSagaId(), event.getTraceId());
                orderService.handleConfirmed(event);
            } else {
                log.warn("Unexpected event type on {}: {}", Topics.ORDER_CONFIRMED,
                        record.value().getClass().getSimpleName());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderConfirmedEvent key={}: {}", record.key(), e.getMessage(), e);
            // Do NOT acknowledge — allow redelivery
        }
    }

    @KafkaListener(
            topics = Topics.ORDER_FAILED,
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderFailed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderFailedEvent event) {
                log.info("Received OrderFailedEvent orderId={} sagaId={} traceId={} reason={}",
                        event.getOrderId(), event.getSagaId(), event.getTraceId(), event.getReason());
                orderService.handleFailed(event);
            } else {
                log.warn("Unexpected event type on {}: {}", Topics.ORDER_FAILED,
                        record.value().getClass().getSimpleName());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderFailedEvent key={}: {}", record.key(), e.getMessage(), e);
            // Do NOT acknowledge — allow redelivery
        }
    }
}
