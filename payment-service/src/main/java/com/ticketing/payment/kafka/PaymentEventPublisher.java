package com.ticketing.payment.kafka;

import com.ticketing.common.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes payment-domain events to Kafka.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private static final String ADMIN_RECIPIENT = "admin@ticketing.com";

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishPaymentSucceeded(PaymentChargeCommand cmd, String reference) {
        var event = new PaymentSucceededEvent(
                cmd.getTraceId(), cmd.getSagaId(),
                cmd.getOrderId(), cmd.getUserId(), cmd.getAmount(), reference);
        send(Topics.PAYMENT_SUCCEEDED, cmd.getOrderId(), event);
        log.info("[Publisher] PaymentSucceeded sent for orderId={}", cmd.getOrderId());
    }

    public void publishPaymentFailed(PaymentChargeCommand cmd, String reason, int attemptCount) {
        var event = new PaymentFailedEvent(
                cmd.getTraceId(), cmd.getSagaId(),
                cmd.getOrderId(), cmd.getUserId(), reason, attemptCount);
        send(Topics.PAYMENT_FAILED, cmd.getOrderId(), event);
        log.warn("[Publisher] PaymentFailed sent for orderId={}", cmd.getOrderId());
    }

    public void publishPaymentDlq(PaymentChargeCommand cmd, String reason, int attemptCount) {
        var event = new PaymentFailedEvent(
                cmd.getTraceId(), cmd.getSagaId(),
                cmd.getOrderId(), cmd.getUserId(), reason, attemptCount);
        send(Topics.PAYMENT_DLQ, cmd.getOrderId(), event);
    }

    public void publishAdminNotification(PaymentChargeCommand cmd, String reason) {
        var notification = new NotificationSendCommand(
                cmd.getTraceId(), cmd.getSagaId(),
                "ADMIN_ALERT", ADMIN_RECIPIENT,
                "Payment failed for order " + cmd.getOrderId(),
                "Payment for orderId=" + cmd.getOrderId()
                        + " userId=" + cmd.getUserId()
                        + " failed after " + 3 + " attempts. Reason: " + reason,
                cmd.getOrderId());
        send(Topics.NOTIFICATION_SEND, cmd.getOrderId(), notification);
    }

    /**
     * Published when a charge is refunded because the saga was unwound.
     * Called from {@link com.ticketing.payment.service.PaymentService#processPayment}
     * (cancellation mid-flight) or {@link com.ticketing.payment.service.PaymentService#cancelPayment}
     * (cancellation after success).
     */
    public void publishPaymentRefunded(PaymentChargeCommand cmd, String reference, String reason) {
        var event = new PaymentRefundedEvent(
                cmd.getTraceId(), cmd.getSagaId(),
                cmd.getOrderId(), cmd.getUserId(),
                cmd.getAmount(), reference, reason);
        send(Topics.PAYMENT_REFUNDED, cmd.getOrderId(), event);
        log.warn("[Publisher] PaymentRefunded sent for orderId={} reason={}", cmd.getOrderId(), reason);
    }

    /**
     * Variant used when the refund is triggered by a {@link com.ticketing.common.events.PaymentCancelCommand}
     * (no original {@link com.ticketing.common.events.PaymentChargeCommand} reference available).
     */
    public void publishPaymentRefundedDirect(String traceId, String sagaId,
                                              String orderId, String userId,
                                              BigDecimal amount, String reference, String reason) {
        var event = new PaymentRefundedEvent(traceId, sagaId, orderId, userId, amount, reference, reason);
        send(Topics.PAYMENT_REFUNDED, orderId, event);
        log.warn("[Publisher] PaymentRefunded sent for orderId={} reason={}", orderId, reason);
    }

    private void send(String topic, String key, DomainEvent event) {
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Publisher] Failed to send to topic={} key={}: {}", topic, key, ex.getMessage());
                    } else {
                        log.debug("[Publisher] Sent to topic={} offset={}", topic,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
