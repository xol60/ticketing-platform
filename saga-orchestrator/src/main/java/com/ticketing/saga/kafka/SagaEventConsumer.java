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

    // -------------------------------------------------------------------------
    // Step 1: order.created -> start saga
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.ORDER_CREATED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderCreated(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof OrderCreatedEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received OrderCreatedEvent: orderId={}, traceId={}",
                    event.getOrderId(), event.getTraceId());
            orchestrator.startSaga(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing OrderCreatedEvent from topic={}: {}", topic, e.getMessage(), e);
            // Acknowledge to avoid poison-pill loop; consider DLQ routing for production
            ack.acknowledge();
        }
    }

    // -------------------------------------------------------------------------
    // Step 2: ticket.reserved -> advance saga
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.TICKET_RESERVED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReserved(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof TicketReservedEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received TicketReservedEvent: sagaId={}, ticketId={}, traceId={}",
                    event.getSagaId(), event.getTicketId(), event.getTraceId());
            orchestrator.onTicketReserved(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReservedEvent from topic={}: {}", topic, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // -------------------------------------------------------------------------
    // Step 3: pricing.locked -> advance saga
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.PRICING_LOCKED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPricingLocked(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof PricingLockedEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received PricingLockedEvent: sagaId={}, ticketId={}, traceId={}",
                    event.getSagaId(), event.getTicketId(), event.getTraceId());
            orchestrator.onPricingLocked(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PricingLockedEvent from topic={}: {}", topic, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // -------------------------------------------------------------------------
    // Step 4: payment.succeeded -> advance saga
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.PAYMENT_SUCCEEDED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentSucceeded(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof PaymentSucceededEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received PaymentSucceededEvent: sagaId={}, orderId={}, traceId={}",
                    event.getSagaId(), event.getOrderId(), event.getTraceId());
            orchestrator.onPaymentSucceeded(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PaymentSucceededEvent from topic={}: {}", topic, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // -------------------------------------------------------------------------
    // Step 5: ticket.confirmed -> complete saga
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.TICKET_CONFIRMED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof TicketConfirmedEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.info("Received TicketConfirmedEvent: sagaId={}, ticketId={}, traceId={}",
                    event.getSagaId(), event.getTicketId(), event.getTraceId());
            orchestrator.onTicketConfirmed(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketConfirmedEvent from topic={}: {}", topic, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // -------------------------------------------------------------------------
    // Compensation: payment.failed
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.PAYMENT_FAILED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentFailed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof PaymentFailedEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.warn("Received PaymentFailedEvent: sagaId={}, orderId={}, reason={}, traceId={}",
                    event.getSagaId(), event.getOrderId(), event.getFailureReason(), event.getTraceId());
            orchestrator.onPaymentFailed(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PaymentFailedEvent from topic={}: {}", topic, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // -------------------------------------------------------------------------
    // Compensation: ticket.released
    // -------------------------------------------------------------------------

    @KafkaListener(topics = Topics.TICKET_RELEASED,
                   groupId = "saga-orchestrator",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketReleased(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        String topic = record.topic();
        try {
            if (!(record.value() instanceof TicketReleasedEvent event)) {
                log.warn("Unexpected event type on {}: {}", topic, record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            log.warn("Received TicketReleasedEvent: sagaId={}, ticketId={}, reason={}, traceId={}",
                    event.getSagaId(), event.getTicketId(), event.getReason(), event.getTraceId());
            orchestrator.onTicketReleased(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReleasedEvent from topic={}: {}", topic, e.getMessage(), e);
            ack.acknowledge();
        }
    }
}
