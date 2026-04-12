package com.ticketing.saga.kafka;

import com.ticketing.common.events.*;
import com.ticketing.saga.service.SagaOrchestrator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class SagaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SagaEventConsumer.class);

    private final SagaOrchestrator orchestrator;

    public SagaEventConsumer(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — order.created → start saga
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.ORDER_CREATED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderCreated(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof OrderCreatedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received OrderCreatedEvent: orderId={} traceId={}", event.getOrderId(), event.getTraceId());
            orchestrator.startSaga(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderCreatedEvent: {}", e.getMessage(), e);
            ack.acknowledge(); // Avoid poison-pill; consider DLQ routing in production
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — ticket.reserved → send price lock
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.TICKET_RESERVED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReserved(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof TicketReservedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received TicketReservedEvent: sagaId={} ticketId={}", event.getSagaId(), event.getTicketId());
            orchestrator.onTicketReserved(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReservedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3a — pricing.locked → send payment charge
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.PRICING_LOCKED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPricingLocked(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof PricingLockedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received PricingLockedEvent: sagaId={} ticketId={}", event.getSagaId(), event.getTicketId());
            orchestrator.onPricingLocked(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PricingLockedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3b — pricing.price.changed → pause saga, notify user
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.PRICING_PRICE_CHANGED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPriceChanged(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof PriceChangedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received PriceChangedEvent: sagaId={} orderId={} oldPrice={} newPrice={}",
                    event.getSagaId(), event.getOrderId(), event.getOldPrice(), event.getNewPrice());
            orchestrator.onPriceChanged(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PriceChangedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3c — pricing.failed → fabricated price OR system error
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.PRICING_FAILED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPricingFailed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof PricingFailedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.warn("Received PricingFailedEvent: sagaId={} orderId={} reason={}",
                    event.getSagaId(), event.getOrderId(), event.getReason());
            orchestrator.onPricingFailed(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PricingFailedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3d — order.price.confirm → user confirmed price change → resume
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.ORDER_PRICE_CONFIRM,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPriceConfirm(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof OrderPriceConfirmCommand cmd)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received OrderPriceConfirmCommand: sagaId={} orderId={} userId={}",
                    cmd.getSagaId(), cmd.getOrderId(), cmd.getUserId());
            orchestrator.onPriceConfirmReceived(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderPriceConfirmCommand: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3e — order.price.cancel → user rejected price change → cancel saga
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.ORDER_PRICE_CANCEL,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPriceCancel(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof OrderPriceCancelCommand cmd)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received OrderPriceCancelCommand: sagaId={} orderId={} userId={}",
                    cmd.getSagaId(), cmd.getOrderId(), cmd.getUserId());
            orchestrator.onPriceCancelReceived(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderPriceCancelCommand: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4 — payment.succeeded → confirm ticket
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.PAYMENT_SUCCEEDED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentSucceeded(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof PaymentSucceededEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received PaymentSucceededEvent: sagaId={} orderId={}", event.getSagaId(), event.getOrderId());
            orchestrator.onPaymentSucceeded(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PaymentSucceededEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 5 — ticket.confirmed → complete saga
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.TICKET_CONFIRMED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof TicketConfirmedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received TicketConfirmedEvent: sagaId={} ticketId={}", event.getSagaId(), event.getTicketId());
            orchestrator.onTicketConfirmed(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketConfirmedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compensation — payment.failed
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.PAYMENT_FAILED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentFailed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof PaymentFailedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.warn("Received PaymentFailedEvent: sagaId={} orderId={} reason={}",
                    event.getSagaId(), event.getOrderId(), event.getFailureReason());
            orchestrator.onPaymentFailed(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PaymentFailedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compensation — ticket.released
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(topics = Topics.TICKET_RELEASED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReleased(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof TicketReleasedEvent event)) {
                log.warn("Unexpected type on {}: {}", record.topic(), record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.warn("Received TicketReleasedEvent: sagaId={} ticketId={} reason={}",
                    event.getSagaId(), event.getTicketId(), event.getReason());
            orchestrator.onTicketReleased(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReleasedEvent: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }
}
