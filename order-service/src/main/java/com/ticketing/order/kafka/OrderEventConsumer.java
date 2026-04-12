package com.ticketing.order.kafka;

import com.ticketing.common.events.*;
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

    @KafkaListener(topics = Topics.ORDER_CONFIRMED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderConfirmedEvent event) {
                log.info("OrderConfirmedEvent orderId={} sagaId={}", event.getOrderId(), event.getSagaId());
                orderService.handleConfirmed(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderConfirmedEvent: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = Topics.ORDER_FAILED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderFailed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderFailedEvent event) {
                log.info("OrderFailedEvent orderId={} reason={}", event.getOrderId(), event.getReason());
                orderService.handleFailed(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderFailedEvent: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = Topics.ORDER_PRICE_CHANGED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderPriceChanged(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderPriceChangedEvent event) {
                log.info("OrderPriceChangedEvent orderId={} oldPrice={} newPrice={}",
                        event.getOrderId(), event.getOldPrice(), event.getNewPrice());
                orderService.handlePriceChanged(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderPriceChangedEvent: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED, groupId = "order-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderCancelled(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof OrderCancelledEvent event) {
                log.info("OrderCancelledEvent orderId={} reason={}", event.getOrderId(), event.getReason());
                orderService.handleCancelled(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderCancelledEvent: {}", e.getMessage(), e);
        }
    }
}
