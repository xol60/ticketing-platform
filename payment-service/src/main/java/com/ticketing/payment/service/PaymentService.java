package com.ticketing.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.PaymentChargeCommand;
import com.ticketing.common.events.NotificationSendCommand;
import com.ticketing.payment.domain.model.Payment;
import com.ticketing.payment.domain.model.PaymentStatus;
import com.ticketing.payment.domain.repository.PaymentRepository;
import com.ticketing.payment.dto.response.PaymentResponse;
import com.ticketing.payment.kafka.PaymentEventPublisher;
import com.ticketing.payment.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Core payment processing service.
 *
 * Retry strategy: up to 3 attempts with exponential backoff (1s / 2s / 4s).
 * On exhaustion: publishes payment.failed + payment.dlq + notification.send (ADMIN_ALERT).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int    MAX_ATTEMPTS   = 3;
    private static final long[] BACKOFF_MS     = {1_000, 2_000, 4_000};
    private static final String REDIS_PREFIX   = "payment:";
    private static final Duration REDIS_TTL    = Duration.ofMinutes(5);
    private static final String ADMIN_RECIPIENT = "admin@ticketing.com";

    private final PaymentRepository      paymentRepository;
    private final ExternalPaymentGateway gateway;
    private final PaymentEventPublisher  publisher;
    private final PaymentMapper          mapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper           objectMapper;
    private final TransactionTemplate    txTemplate;

    // -----------------------------------------------------------------------
    // Command handling
    // -----------------------------------------------------------------------

    // No @Transactional here — each DB operation opens its own short transaction
    @CacheEvict(value = "payments", key = "#cmd.orderId")
    public void processPayment(PaymentChargeCommand cmd) {
        log.info("[PaymentService] Processing payment for orderId={} sagaId={}", cmd.getOrderId(), cmd.getSagaId());

        // TX 1: save PENDING — short, closes immediately
        Payment payment = txTemplate.execute(status -> {
            Payment p = Payment.builder()
                    .id(UUID.randomUUID())
                    .orderId(cmd.getOrderId())
                    .userId(cmd.getUserId())
                    .ticketId(cmd.getTicketId())
                    .amount(cmd.getAmount())
                    .status(PaymentStatus.PENDING)
                    .attemptCount(0)
                    .build();
            return paymentRepository.save(p);
        });

        String lastFailureReason = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // External call — no transaction held open during network I/O
                ExternalPaymentGateway.PaymentResult result = gateway.charge(cmd.getOrderId(), attempt);

                // TX 2: save SUCCESS — short, closes immediately
                final int finalAttempt = attempt;
                txTemplate.execute(status -> {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setPaymentReference(result.reference());
                    payment.setAttemptCount(finalAttempt);
                    paymentRepository.save(payment);
                    redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());
                    return null;
                });

                publisher.publishPaymentSucceeded(cmd, result.reference());
                log.info("[PaymentService] Payment succeeded orderId={} ref={}", cmd.getOrderId(), result.reference());
                return;

            } catch (ExternalPaymentGateway.PaymentGatewayException ex) {
                lastFailureReason = ex.getMessage();
                log.warn("[PaymentService] Attempt {}/{} failed for orderId={}: {}",
                        attempt, MAX_ATTEMPTS, cmd.getOrderId(), lastFailureReason);
                if (attempt < MAX_ATTEMPTS) {
                    sleep(BACKOFF_MS[attempt - 1]);
                }
            }
        }

        // TX 3: save FAILED — short, closes immediately
        final String reason = lastFailureReason;
        txTemplate.execute(status -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(reason);
            payment.setAttemptCount(MAX_ATTEMPTS);
            paymentRepository.save(payment);
            redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());
            return null;
        });

        publisher.publishPaymentFailed(cmd, reason, MAX_ATTEMPTS);
        publisher.publishPaymentDlq(cmd, reason, MAX_ATTEMPTS);
        publisher.publishAdminNotification(cmd, reason);
        log.error("[PaymentService] Payment failed after {} attempts for orderId={}", MAX_ATTEMPTS, cmd.getOrderId());
    }

    // -----------------------------------------------------------------------
    // Read path (L1 Caffeine → L2 Redis → DB)
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    @Cacheable(value = "payments", key = "#orderId")
    public PaymentResponse getPaymentByOrderId(String orderId) {
        // Try L2 Redis first
        String cached = redisTemplate.opsForValue().get(REDIS_PREFIX + orderId);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, PaymentResponse.class);
            } catch (Exception e) {
                log.warn("[PaymentService] Failed to deserialize Redis cache for orderId={}", orderId);
            }
        }

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new com.ticketing.payment.exception.PaymentNotFoundException(orderId));

        PaymentResponse response = mapper.toResponse(payment);

        // Populate L2 Redis
        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + orderId,
                    objectMapper.writeValueAsString(response), REDIS_TTL);
        } catch (Exception e) {
            log.warn("[PaymentService] Failed to cache payment in Redis for orderId={}", orderId);
        }

        return response;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
