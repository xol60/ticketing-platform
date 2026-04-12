package com.ticketing.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates an external payment gateway.
 * 80% success rate, 20% failure rate.
 */
@Component
@Slf4j
public class ExternalPaymentGateway {

    /**
     * Attempt a charge. Throws PaymentGatewayException on simulated failure.
     *
     * @param orderId  order identifier (used as correlation key)
     * @param attempt  current attempt number (1-based)
     * @return PaymentResult with success=true and a generated reference
     */
    public PaymentResult charge(String orderId, int attempt) {
        double roll = ThreadLocalRandom.current().nextDouble();
        log.info("[ExternalGateway] orderId={} attempt={} roll={}", orderId, attempt, roll);

        if (roll < 0.80) {
            String reference = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.info("[ExternalGateway] SUCCESS orderId={} reference={}", orderId, reference);
            return new PaymentResult(true, reference, null);
        }

        String reason = "Simulated gateway failure on attempt " + attempt;
        log.warn("[ExternalGateway] FAILURE orderId={} reason={}", orderId, reason);
        throw new PaymentGatewayException(reason);
    }

    // --- Inner types --------------------------------------------------------

    public record PaymentResult(boolean success, String reference, String failureReason) {}

    public static class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
