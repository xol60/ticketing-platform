package com.ticketing.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates an external payment gateway (e.g. Stripe, Braintree).
 *
 * <p>charge() — 80% success, 20% failure.
 * <p>refund()  — always succeeds (real gateways can fail; a production implementation
 *               would retry with exponential back-off and alert on DLQ).
 */
@Component
@Slf4j
public class ExternalPaymentGateway {

    /**
     * Attempt a charge. Throws {@link PaymentGatewayException} on simulated failure.
     *
     * @param orderId  order identifier (used as idempotency/correlation key)
     * @param attempt  current attempt number (1-based)
     * @return PaymentResult with a generated reference on success
     */
    public PaymentResult charge(String orderId, int attempt) {
        double roll = ThreadLocalRandom.current().nextDouble();
        log.info("[ExternalGateway] CHARGE orderId={} attempt={} roll={:.2f}", orderId, attempt, roll);

        if (roll < 0.80) {
            String reference = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.info("[ExternalGateway] CHARGE SUCCESS orderId={} reference={}", orderId, reference);
            return new PaymentResult(true, reference, null);
        }

        String reason = "Simulated gateway failure on attempt " + attempt;
        log.warn("[ExternalGateway] CHARGE FAILURE orderId={} reason={}", orderId, reason);
        throw new PaymentGatewayException(reason);
    }

    /**
     * Refund a previously successful charge.
     *
     * <p>In this simulation refunds always succeed. A production implementation should:
     * <ul>
     *   <li>Retry on transient gateway errors</li>
     *   <li>Publish to a DLQ and alert on permanent failure</li>
     *   <li>Handle "already refunded" idempotency from the gateway</li>
     * </ul>
     *
     * @param orderId   order identifier for logging / idempotency
     * @param reference the {@code paymentReference} returned by the original charge
     */
    public void refund(String orderId, String reference) {
        log.info("[ExternalGateway] REFUND orderId={} reference={}", orderId, reference);
        // Always succeeds in simulation.
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    public record PaymentResult(boolean success, String reference, String failureReason) {}

    public static class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
