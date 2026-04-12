package com.ticketing.saga.kafka;

import com.ticketing.common.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SagaCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(SagaCommandPublisher.class);

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public SagaCommandPublisher(KafkaTemplate<String, DomainEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendTicketReserveCommand(String traceId, String sagaId,
                                         String ticketId, String orderId, String userId) {
        TicketReserveCommand cmd = new TicketReserveCommand(traceId, sagaId, ticketId, orderId, userId);
        log.info("Publishing TicketReserveCommand: sagaId={}, ticketId={}, orderId={}", sagaId, ticketId, orderId);
        kafkaTemplate.send(Topics.TICKET_RESERVE_CMD, orderId, cmd);
    }

    public void sendPriceLockCommand(String traceId, String sagaId,
                                      String ticketId, String orderId,
                                      String eventId, BigDecimal requestedPrice) {
        PriceLockCommand cmd = new PriceLockCommand(traceId, sagaId, ticketId, orderId, eventId, requestedPrice);
        log.info("Publishing PriceLockCommand: sagaId={}, ticketId={}, orderId={}", sagaId, ticketId, orderId);
        kafkaTemplate.send(Topics.PRICING_LOCK_CMD, orderId, cmd);
    }

    public void sendPaymentChargeCommand(String traceId, String sagaId,
                                          String orderId, String userId,
                                          String ticketId, BigDecimal amount) {
        PaymentChargeCommand cmd = new PaymentChargeCommand(traceId, sagaId, orderId, userId, ticketId, amount);
        log.info("Publishing PaymentChargeCommand: sagaId={}, orderId={}, amount={}", sagaId, orderId, amount);
        kafkaTemplate.send(Topics.PAYMENT_CHARGE_CMD, orderId, cmd);
    }

    public void sendTicketConfirmCommand(String traceId, String sagaId,
                                          String ticketId, String orderId) {
        TicketConfirmCommand cmd = new TicketConfirmCommand(traceId, sagaId, ticketId, orderId);
        log.info("Publishing TicketConfirmCommand: sagaId={}, ticketId={}, orderId={}", sagaId, ticketId, orderId);
        kafkaTemplate.send(Topics.TICKET_CONFIRM_CMD, orderId, cmd);
    }

    public void sendTicketReleaseCommand(String traceId, String sagaId,
                                          String ticketId, String orderId, String reason) {
        TicketReleaseCommand cmd = new TicketReleaseCommand(traceId, sagaId, ticketId, orderId, reason);
        log.info("Publishing TicketReleaseCommand: sagaId={}, ticketId={}, orderId={}, reason={}",
                sagaId, ticketId, orderId, reason);
        kafkaTemplate.send(Topics.TICKET_RELEASE_CMD, orderId, cmd);
    }

    public void sendPriceUnlockCommand(String traceId, String sagaId,
                                        String ticketId, String orderId, String reason) {
        PriceUnlockCommand cmd = new PriceUnlockCommand(traceId, sagaId, ticketId, orderId, reason);
        log.info("Publishing PriceUnlockCommand: sagaId={}, ticketId={}, orderId={}, reason={}",
                sagaId, ticketId, orderId, reason);
        kafkaTemplate.send(Topics.PRICING_UNLOCK_CMD, orderId, cmd);
    }

    public void publishOrderConfirmed(String traceId, String sagaId,
                                       String orderId, String userId,
                                       String ticketId, BigDecimal finalPrice,
                                       String paymentReference) {
        OrderConfirmedEvent event = new OrderConfirmedEvent(traceId, sagaId, orderId, userId,
                ticketId, finalPrice, paymentReference);
        log.info("Publishing OrderConfirmedEvent: sagaId={}, orderId={}", sagaId, orderId);
        kafkaTemplate.send(Topics.ORDER_CONFIRMED, orderId, event);
    }

    public void publishOrderFailed(String traceId, String sagaId,
                                    String orderId, String userId,
                                    String ticketId, String reason) {
        OrderFailedEvent event = new OrderFailedEvent(traceId, sagaId, orderId, userId, ticketId, reason);
        log.info("Publishing OrderFailedEvent: sagaId={}, orderId={}, reason={}", sagaId, orderId, reason);
        kafkaTemplate.send(Topics.ORDER_FAILED, orderId, event);
    }

    public void publishSagaCompensate(String traceId, String sagaId,
                                       String failedStep, String orderId,
                                       String ticketId, String reason) {
        SagaCompensateEvent event = new SagaCompensateEvent(traceId, sagaId, failedStep, orderId, ticketId, reason);
        log.info("Publishing SagaCompensateEvent: sagaId={}, failedStep={}, orderId={}", sagaId, failedStep, orderId);
        kafkaTemplate.send(Topics.SAGA_COMPENSATE, orderId, event);
    }
}
