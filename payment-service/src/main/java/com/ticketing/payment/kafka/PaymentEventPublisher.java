package com.ticketing.payment.kafka;

import com.ticketing.common.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
                cmd.getTraceId(),
                cmd.getSagaId(),
                cmd.getOrderId(),
                cmd.getUserId(),
                cmd.getAmount(),
                reference
        );
        send(Topics.PAYMENT_SUCCEEDED, cmd.getOrderId(), event);
        log.info("[Publisher] PaymentSucceeded sent for orderId={}", cmd.getOrderId());
    }

    public void publishPaymentFailed(PaymentChargeCommand cmd, String reason, int attemptCount) {
        var event = new PaymentFailedEvent(
                cmd.getTraceId(),
                cmd.getSagaId(),
                cmd.getOrderId(),
                cmd.getUserId(),
                reason,
                attemptCount
        );
        send(Topics.PAYMENT_FAILED, cmd.getOrderId(), event);
        log.warn("[Publisher] PaymentFailed sent for orderId={}", cmd.getOrderId());
    }

    public void publishPaymentDlq(PaymentChargeCommand cmd, String reason, int attemptCount) {
        var event = new PaymentFailedEvent(
                cmd.getTraceId(),
                cmd.getSagaId(),
                cmd.getOrderId(),
                cmd.getUserId(),
                reason,
                attemptCount
        );
        send(Topics.PAYMENT_DLQ, cmd.getOrderId(), event);
        log.warn("[Publisher] PaymentDLQ sent for orderId={}", cmd.getOrderId());
    }

    public void publishAdminNotification(PaymentChargeCommand cmd, String reason) {
        var notification = new NotificationSendCommand(
                cmd.getTraceId(),
                cmd.getSagaId(),
                "ADMIN_ALERT",
                ADMIN_RECIPIENT,
                "Payment failed for order " + cmd.getOrderId(),
                "Payment for orderId=" + cmd.getOrderId()
                        + " userId=" + cmd.getUserId()
                        + " failed after " + 3 + " attempts. Reason: " + reason,
                cmd.getOrderId()
        );
        send(Topics.NOTIFICATION_SEND, cmd.getOrderId(), notification);
        log.warn("[Publisher] AdminNotification sent for orderId={}", cmd.getOrderId());
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
