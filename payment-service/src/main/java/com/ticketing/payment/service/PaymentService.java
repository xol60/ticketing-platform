package com.ticketing.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.PaymentCancelCommand;
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
 * <h3>Retry strategy</h3>
 * Up to 3 attempts with exponential backoff (1 s / 2 s / 4 s).
 * On exhaustion: publishes payment.failed + payment.dlq + notification.send (ADMIN_ALERT).
 *
 * <h3>Cancellation / refund handling</h3>
 * A {@link PaymentCancelCommand} can arrive at any point:
 * <ul>
 *   <li><b>While gateway call is in flight (status = PENDING)</b>: the record is
 *       marked {@code CANCELLATION_REQUESTED}. After {@code gateway.charge()} returns
 *       success, {@link #processPayment} re-reads the row inside a transaction and,
 *       if it sees {@code CANCELLATION_REQUESTED}, immediately calls
 *       {@code gateway.refund()} instead of publishing {@code PaymentSucceededEvent}.
 *       This prevents the saga from advancing on a charge the orchestrator already
 *       decided to unwind.</li>
 *   <li><b>After gateway already returned success (status = SUCCESS)</b>:
 *       {@link #cancelPayment} calls {@code gateway.refund()} immediately and
 *       publishes {@code PaymentRefundedEvent}.</li>
 * </ul>
 * Both paths are idempotent: a second cancel on a {@code REFUNDED} record is a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int    MAX_ATTEMPTS    = 3;
    private static final long[] BACKOFF_MS      = {1_000, 2_000, 4_000};
    private static final String REDIS_PREFIX    = "payment:";
    private static final Duration REDIS_TTL     = Duration.ofMinutes(5);

    private final PaymentRepository      paymentRepository;
    private final ExternalPaymentGateway gateway;
    private final PaymentEventPublisher  publisher;
    private final PaymentMapper          mapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper           objectMapper;
    private final TransactionTemplate    txTemplate;

    // ── Command: charge ──────────────────────────────────────────────────────

    // No @Transactional — each DB operation opens its own short transaction so
    // no connection is held open during the blocking external gateway call.
    @CacheEvict(value = "payments", key = "#cmd.orderId")
    public void processPayment(PaymentChargeCommand cmd) {
        log.info("[PaymentService] Processing payment for orderId={} sagaId={}", cmd.getOrderId(), cmd.getSagaId());

        // TX 1: save PENDING — short, closes immediately
        Payment payment = txTemplate.execute(status -> {
            // Idempotency: if a record already exists (Kafka at-least-once re-delivery),
            // skip re-creation and work with the existing one.
            return paymentRepository.findByOrderId(cmd.getOrderId()).orElseGet(() -> {
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
        });

        // If a cancel arrived before we even started, skip the gateway entirely.
        if (payment.getStatus() == PaymentStatus.CANCELLATION_REQUESTED
                || payment.getStatus() == PaymentStatus.REFUNDED) {
            log.warn("[PaymentService] Payment already cancelled before charge attempt for orderId={}", cmd.getOrderId());
            publisher.publishPaymentRefunded(cmd, null, "CANCELLED_BEFORE_CHARGE");
            return;
        }

        String lastFailureReason = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // External call — no transaction held open during network I/O
                ExternalPaymentGateway.PaymentResult result = gateway.charge(cmd.getOrderId(), attempt);
                final String ref = result.reference();
                final int finalAttempt = attempt;

                // TX 2: atomically check for concurrent cancellation and write result.
                // Re-read within the transaction so we see any CANCELLATION_REQUESTED flag
                // that a concurrent cancelPayment() may have written while we were waiting
                // for the gateway to respond.
                boolean refundImmediately = Boolean.TRUE.equals(txTemplate.execute(txStatus -> {
                    Payment fresh = paymentRepository.findById(payment.getId()).orElseThrow();
                    redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());

                    if (fresh.getStatus() == PaymentStatus.CANCELLATION_REQUESTED) {
                        // Cancellation arrived while gateway was in flight — mark REFUNDED.
                        // The actual gateway.refund() call happens outside this TX (below).
                        fresh.setStatus(PaymentStatus.REFUNDED);
                        fresh.setPaymentReference(ref);
                        fresh.setAttemptCount(finalAttempt);
                        paymentRepository.save(fresh);
                        return true;
                    }

                    // Normal success path
                    fresh.setStatus(PaymentStatus.SUCCESS);
                    fresh.setPaymentReference(ref);
                    fresh.setAttemptCount(finalAttempt);
                    paymentRepository.save(fresh);
                    return false;
                }));

                if (refundImmediately) {
                    // Money moved but cancellation was requested — refund immediately.
                    log.warn("[PaymentService] Cancellation was requested mid-flight for orderId={} ref={} — refunding",
                            cmd.getOrderId(), ref);
                    gateway.refund(cmd.getOrderId(), ref);
                    publisher.publishPaymentRefunded(cmd, ref, "CANCELLATION_REQUESTED_MID_FLIGHT");
                } else {
                    publisher.publishPaymentSucceeded(cmd, ref);
                    log.info("[PaymentService] Payment succeeded orderId={} ref={}", cmd.getOrderId(), ref);
                }
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

    // ── Command: cancel / refund ─────────────────────────────────────────────

    /**
     * Handles a {@link PaymentCancelCommand} from the saga orchestrator.
     *
     * <p>Idempotent: a second cancel on an already-REFUNDED or FAILED record is a no-op.
     *
     * @param cmd the cancel command carrying the orderId and optional paymentReference
     */
    @CacheEvict(value = "payments", key = "#cmd.orderId")
    public void cancelPayment(PaymentCancelCommand cmd) {
        log.warn("[PaymentService] Cancel requested for orderId={} sagaId={} ref={}",
                cmd.getOrderId(), cmd.getSagaId(), cmd.getPaymentReference());

        // Capture fields for the post-transaction refund call (avoids holding a DB connection during network I/O).
        final String[]           refToRefund = {null};
        final java.math.BigDecimal[] amount  = {null};
        final String[]           userId      = {null};

        txTemplate.execute(txStatus -> {
            Payment payment = paymentRepository.findByOrderId(cmd.getOrderId()).orElse(null);
            if (payment == null) {
                log.warn("[PaymentService] Cancel: no payment record for orderId={}", cmd.getOrderId());
                return null;
            }

            switch (payment.getStatus()) {
                case PENDING -> {
                    // Gateway call still in flight — mark so processPayment refunds on success.
                    log.info("[PaymentService] Cancel: marking CANCELLATION_REQUESTED for orderId={}", cmd.getOrderId());
                    payment.setStatus(PaymentStatus.CANCELLATION_REQUESTED);
                    paymentRepository.save(payment);
                    redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());
                }
                case SUCCESS -> {
                    // Already charged — refund immediately.
                    log.warn("[PaymentService] Cancel: payment already succeeded for orderId={} — refunding ref={}",
                            cmd.getOrderId(), payment.getPaymentReference());
                    refToRefund[0] = payment.getPaymentReference();
                    amount[0]      = payment.getAmount();
                    userId[0]      = payment.getUserId();
                    payment.setStatus(PaymentStatus.REFUNDED);
                    paymentRepository.save(payment);
                    redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());
                }
                case CANCELLATION_REQUESTED, REFUNDED ->
                    log.info("[PaymentService] Cancel: already in progress/done for orderId={} status={}",
                            cmd.getOrderId(), payment.getStatus());
                case FAILED ->
                    log.info("[PaymentService] Cancel: no-op for orderId={} (payment failed, nothing to refund)",
                            cmd.getOrderId());
            }
            return null;
        });

        // Call gateway.refund() *outside* the transaction — network I/O should not hold a DB connection.
        if (refToRefund[0] != null) {
            gateway.refund(cmd.getOrderId(), refToRefund[0]);
            publisher.publishPaymentRefundedDirect(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getOrderId(), userId[0],
                    amount[0], refToRefund[0], "SAGA_CANCEL");
        }
    }

    // ── Read path (L1 Caffeine → L2 Redis → DB) ──────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "payments", key = "#orderId")
    public PaymentResponse getPaymentByOrderId(String orderId) {
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

        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + orderId,
                    objectMapper.writeValueAsString(response), REDIS_TTL);
        } catch (Exception e) {
            log.warn("[PaymentService] Failed to cache payment in Redis for orderId={}", orderId);
        }

        return response;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
