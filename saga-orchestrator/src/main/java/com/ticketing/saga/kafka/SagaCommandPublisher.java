package com.ticketing.saga.kafka;

import com.ticketing.common.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

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
        log.info("Publishing TicketReserveCommand: sagaId={} ticketId={} orderId={}", sagaId, ticketId, orderId);
        // Uses TICKET_CMD (unified command topic) — all ticket commands for the same orderId
        // land on the same partition → reserve, confirm, and release are consumed in order.
        kafkaTemplate.send(Topics.TICKET_CMD, orderId, cmd);
    }

    /**
     * @param userPrice      price the user submitted (for history validation)
     * @param orderCreatedAt DB-stamped order creation time (for point-in-time lookup)
     * @param confirmed      true when user has already agreed to a price change — skip validation
     */
    public void sendPriceLockCommand(String traceId, String sagaId,
                                      String ticketId, String orderId, String eventId,
                                      BigDecimal userPrice, BigDecimal facePrice,
                                      Instant orderCreatedAt, boolean confirmed) {
        PriceLockCommand cmd = new PriceLockCommand(
                traceId, sagaId, ticketId, orderId, eventId, userPrice, facePrice, orderCreatedAt, confirmed);
        log.info("Publishing PriceLockCommand: sagaId={} ticketId={} orderId={} confirmed={}",
                sagaId, ticketId, orderId, confirmed);
        kafkaTemplate.send(Topics.PRICING_LOCK_CMD, orderId, cmd);
    }

    public void sendPaymentChargeCommand(String traceId, String sagaId,
                                          String orderId, String userId,
                                          String ticketId, BigDecimal amount) {
        PaymentChargeCommand cmd = new PaymentChargeCommand(traceId, sagaId, orderId, userId, ticketId, amount);
        log.info("Publishing PaymentChargeCommand: sagaId={} orderId={} amount={}", sagaId, orderId, amount);
        // Uses PAYMENT_CMD (unified command topic) so charge and cancel for the same
        // orderId always land on the same partition → sequential, ordered consumption.
        kafkaTemplate.send(Topics.PAYMENT_CMD, orderId, cmd);
    }

    public void sendTicketConfirmCommand(String traceId, String sagaId,
                                          String ticketId, String orderId) {
        TicketConfirmCommand cmd = new TicketConfirmCommand(traceId, sagaId, ticketId, orderId);
        log.info("Publishing TicketConfirmCommand: sagaId={} ticketId={} orderId={}", sagaId, ticketId, orderId);
        kafkaTemplate.send(Topics.TICKET_CMD, orderId, cmd);
    }

    public void sendTicketReleaseCommand(String traceId, String sagaId,
                                          String ticketId, String orderId, String reason) {
        TicketReleaseCommand cmd = new TicketReleaseCommand(traceId, sagaId, ticketId, orderId, reason);
        log.info("Publishing TicketReleaseCommand: sagaId={} ticketId={} orderId={} reason={}",
                sagaId, ticketId, orderId, reason);
        kafkaTemplate.send(Topics.TICKET_CMD, orderId, cmd);
    }

    public void publishOrderConfirmed(String traceId, String sagaId,
                                       String orderId, String userId,
                                       String ticketId, BigDecimal finalPrice,
                                       String paymentReference) {
        OrderConfirmedEvent event = new OrderConfirmedEvent(
                traceId, sagaId, orderId, userId, ticketId, finalPrice, paymentReference);
        log.info("Publishing OrderConfirmedEvent: sagaId={} orderId={}", sagaId, orderId);
        kafkaTemplate.send(Topics.ORDER_CONFIRMED, orderId, event);
    }

    public void publishOrderFailed(String traceId, String sagaId,
                                    String orderId, String userId,
                                    String ticketId, String reason) {
        OrderFailedEvent event = new OrderFailedEvent(traceId, sagaId, orderId, userId, ticketId, reason);
        log.info("Publishing OrderFailedEvent: sagaId={} orderId={} reason={}", sagaId, orderId, reason);
        kafkaTemplate.send(Topics.ORDER_FAILED, orderId, event);
    }

    public void publishOrderCancelled(String traceId, String sagaId,
                                       String orderId, String userId,
                                       String ticketId, String reason) {
        OrderCancelledEvent event = new OrderCancelledEvent(
                traceId, sagaId, orderId, userId, ticketId, reason);
        log.info("Publishing OrderCancelledEvent: sagaId={} orderId={} reason={}", sagaId, orderId, reason);
        kafkaTemplate.send(Topics.ORDER_CANCELLED, orderId, event);
    }

    /** Notifies order-service that the price changed and user confirmation is required. */
    public void publishOrderPriceChanged(String traceId, String sagaId,
                                          String orderId, String userId,
                                          BigDecimal oldPrice, BigDecimal newPrice,
                                          Instant confirmExpiresAt) {
        OrderPriceChangedEvent event = new OrderPriceChangedEvent(
                traceId, sagaId, orderId, userId, oldPrice, newPrice, confirmExpiresAt);
        log.info("Publishing OrderPriceChangedEvent: sagaId={} orderId={} oldPrice={} newPrice={}",
                sagaId, orderId, oldPrice, newPrice);
        kafkaTemplate.send(Topics.ORDER_PRICE_CHANGED, orderId, event);
    }

    public void publishSagaCompensate(String traceId, String sagaId,
                                       String failedStep, String orderId,
                                       String ticketId, String reason) {
        SagaCompensateEvent event = new SagaCompensateEvent(
                traceId, sagaId, failedStep, orderId, ticketId, reason);
        log.info("Publishing SagaCompensateEvent: sagaId={} failedStep={} orderId={}", sagaId, failedStep, orderId);
        kafkaTemplate.send(Topics.SAGA_COMPENSATE, orderId, event);
    }

    /**
     * Tells the payment service to cancel or refund the charge for this order.
     *
     * <p>Sent to {@link Topics#PAYMENT_CMD} with the same {@code orderId} key as
     * {@link #sendPaymentChargeCommand}, guaranteeing they land on the same Kafka
     * partition and are consumed in the order they were produced.
     *
     * @param paymentReference non-null if payment already succeeded (triggers immediate refund);
     *                         null if payment is still in flight (marks CANCELLATION_REQUESTED)
     */
    public void sendPaymentCancelCommand(String traceId, String sagaId,
                                          String orderId, String paymentReference) {
        PaymentCancelCommand cmd = new PaymentCancelCommand(traceId, sagaId, orderId, paymentReference);
        log.warn("Publishing PaymentCancelCommand: sagaId={} orderId={} ref={}", sagaId, orderId, paymentReference);
        kafkaTemplate.send(Topics.PAYMENT_CMD, orderId, cmd);
    }
}
