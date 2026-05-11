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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Core payment processing service.
 *
 * <h3>Retry strategy — watchdog pattern</h3>
 * The consumer thread no longer blocks on gateway calls or sleeps between retries.
 * Instead:
 * <ol>
 *   <li>{@link #processPayment} saves a {@code PENDING} record with
 *       {@code nextRetryAt = now()} and returns immediately, freeing the
 *       Kafka consumer thread to poll the next message.</li>
 *   <li>{@link com.ticketing.payment.watchdog.PaymentRetryWatchdog} runs every
 *       2 seconds, queries PENDING rows whose {@code nextRetryAt ≤ now}, and
 *       calls {@link #attemptRetry} for each one.</li>
 *   <li>{@link #attemptRetry} claims the row (sets {@code nextRetryAt = now + 30s}
 *       so concurrent watchdog pods don't double-charge), calls the gateway, then
 *       writes SUCCESS or schedules the next backoff / marks FAILED.</li>
 * </ol>
 * Up to 3 attempts with backoffs of 5 s / 15 s after failures.
 *
 * <h3>Cancellation / refund handling</h3>
 * A {@link PaymentCancelCommand} can arrive at any point:
 * <ul>
 *   <li><b>While status = PENDING</b> (gateway not yet called): marked
 *       {@code CANCELLATION_REQUESTED}.  {@link #attemptRetry} detects this
 *       before calling the gateway and publishes a refund event immediately.</li>
 *   <li><b>While gateway call is in flight (PENDING, nextRetryAt in future)</b>:
 *       same — marked {@code CANCELLATION_REQUESTED}; {@link #attemptRetry} checks
 *       after the gateway responds and refunds if the charge succeeded.</li>
 *   <li><b>After gateway already returned success (status = SUCCESS)</b>:
 *       {@link #cancelPayment} calls {@code gateway.refund()} immediately.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    static final int      MAX_ATTEMPTS = 3;
    /** Backoffs in seconds after attempt 1, 2 (attempt 3 is the last — no backoff needed). */
    static final long[]   BACKOFF_S    = {5L, 15L};
    /**
     * Watchdog "claim lease": next_retry_at is pushed this far into the future
     * before the gateway call so a second watchdog pod won't pick up the same row
     * concurrently.  Must be longer than the worst-case gateway round-trip.
     */
    static final Duration CLAIM_LEASE  = Duration.ofSeconds(30);

    private static final String   REDIS_PREFIX = "payment:";
    private static final Duration REDIS_TTL    = Duration.ofMinutes(5);

    private final PaymentRepository      paymentRepository;
    private final ExternalPaymentGateway gateway;
    private final PaymentEventPublisher  publisher;
    private final PaymentMapper          mapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper           objectMapper;
    private final TransactionTemplate    txTemplate;

    // ── Command: charge ──────────────────────────────────────────────────────

    /**
     * Called by the Kafka consumer for a {@link PaymentChargeCommand}.
     *
     * <p>Saves a {@code PENDING} record and returns immediately — no blocking,
     * no sleeping.  The actual gateway call is handled by
     * {@link com.ticketing.payment.watchdog.PaymentRetryWatchdog}.
     */
    @CacheEvict(value = "payments", key = "#cmd.orderId")
    public void processPayment(PaymentChargeCommand cmd) {
        log.info("[PaymentService] Scheduling payment for orderId={} sagaId={}", cmd.getOrderId(), cmd.getSagaId());

        txTemplate.execute(status -> {
            // Idempotency: if the record already exists (Kafka at-least-once re-delivery)
            // do not reset it — it may already be in a non-PENDING state.
            if (paymentRepository.findByOrderId(cmd.getOrderId()).isPresent()) {
                log.info("[PaymentService] Duplicate PaymentChargeCommand for orderId={} — skipping", cmd.getOrderId());
                return null;
            }
            Payment p = Payment.builder()
                    .id(UUID.randomUUID())
                    .orderId(cmd.getOrderId())
                    .userId(cmd.getUserId())
                    .ticketId(cmd.getTicketId())
                    .amount(cmd.getAmount())
                    .sagaId(cmd.getSagaId())
                    .traceId(cmd.getTraceId())
                    .status(PaymentStatus.PENDING)
                    .attemptCount(0)
                    .nextRetryAt(Instant.now())   // due immediately
                    .build();
            paymentRepository.save(p);
            log.info("[PaymentService] Payment record created orderId={} nextRetryAt=now", cmd.getOrderId());
            return null;
        });
    }

    // ── Watchdog entry point ─────────────────────────────────────────────────

    /**
     * Called by {@link com.ticketing.payment.watchdog.PaymentRetryWatchdog} for
     * each PENDING payment whose {@code nextRetryAt ≤ now}.
     *
     * <h4>Claim-before-charge</h4>
     * Inside a short transaction the row is "claimed" by pushing
     * {@code nextRetryAt} forward by {@link #CLAIM_LEASE} (30 s).  If two
     * watchdog threads race, the second will encounter an
     * {@link OptimisticLockingFailureException} and skip the row — the
     * {@code @Version} field on {@link Payment} provides the guard.
     *
     * <h4>Gateway call outside TX</h4>
     * The actual {@code gateway.charge()} executes with no DB connection held,
     * so the payment connection pool is never exhausted by slow gateway I/O.
     *
     * @param paymentId the {@link Payment#getId()} to attempt
     */
    public void attemptRetry(UUID paymentId) {
        // ── Step 1: claim the row ────────────────────────────────────────────
        // Captured inside the TX so the post-TX code can use them without
        // re-reading the DB.
        final String[]       orderId  = {null};
        final String[]       sagaId   = {null};
        final String[]       traceId  = {null};
        final String[]       userId   = {null};
        final BigDecimal[]   amount   = {null};
        final int[]          attempt  = {0};
        final boolean[]      proceed  = {false};

        try {
            txTemplate.execute(txStatus -> {
                Payment p = paymentRepository.findById(paymentId).orElse(null);
                if (p == null) return null;

                // Guard: only process if still PENDING and the claim lease has lapsed
                if (p.getStatus() != PaymentStatus.PENDING) {
                    log.debug("[Watchdog] Skipping paymentId={} status={}", paymentId, p.getStatus());
                    return null;
                }
                if (p.getNextRetryAt() == null || p.getNextRetryAt().isAfter(Instant.now())) {
                    log.debug("[Watchdog] Skipping paymentId={} — claim lease still active", paymentId);
                    return null;
                }

                // CANCELLATION_REQUESTED arrived before we even tried: skip charge, refund
                if (p.getStatus() == PaymentStatus.CANCELLATION_REQUESTED) {
                    p.setStatus(PaymentStatus.REFUNDED);
                    p.setNextRetryAt(null);
                    paymentRepository.save(p);
                    redisTemplate.delete(REDIS_PREFIX + p.getOrderId());
                    publisher.publishPaymentRefundedDirect(
                            p.getTraceId(), p.getSagaId(), p.getOrderId(),
                            p.getUserId(), p.getAmount(), null, "CANCELLED_BEFORE_CHARGE");
                    return null;
                }

                int nextAttempt = p.getAttemptCount() + 1;
                if (nextAttempt > MAX_ATTEMPTS) {
                    // Exhausted — mark FAILED (safety net; normally handled after the gateway call)
                    log.warn("[Watchdog] Exhausted retries for paymentId={} orderId={}", paymentId, p.getOrderId());
                    markFailed(p, "Max attempts exhausted");
                    return null;
                }

                // Claim: push next_retry_at forward so no other pod retries concurrently
                p.setAttemptCount(nextAttempt);
                p.setNextRetryAt(Instant.now().plus(CLAIM_LEASE));
                paymentRepository.save(p);  // OptimisticLockException if racing pod also saved

                // Capture for post-TX use
                orderId[0] = p.getOrderId();
                sagaId[0]  = p.getSagaId();
                traceId[0] = p.getTraceId();
                userId[0]  = p.getUserId();
                amount[0]  = p.getAmount();
                attempt[0] = nextAttempt;
                proceed[0] = true;
                return null;
            });
        } catch (OptimisticLockingFailureException ex) {
            log.debug("[Watchdog] Race on paymentId={} — another instance claimed it, skipping", paymentId);
            return;
        }

        if (!proceed[0]) return;

        // ── Step 2: call gateway (no DB connection held) ─────────────────────
        log.info("[Watchdog] Charging orderId={} attempt={}/{}", orderId[0], attempt[0], MAX_ATTEMPTS);
        try {
            ExternalPaymentGateway.PaymentResult result =
                    gateway.charge(orderId[0], attempt[0]);
            String ref = result.reference();

            // ── Step 3a: gateway succeeded ────────────────────────────────────
            txTemplate.execute(txStatus -> {
                Payment fresh = paymentRepository.findById(paymentId).orElseThrow();
                redisTemplate.delete(REDIS_PREFIX + fresh.getOrderId());

                if (fresh.getStatus() == PaymentStatus.CANCELLATION_REQUESTED) {
                    // Cancel arrived while gateway was in flight — refund immediately
                    fresh.setStatus(PaymentStatus.REFUNDED);
                    fresh.setPaymentReference(ref);
                    fresh.setNextRetryAt(null);
                    paymentRepository.save(fresh);
                    log.warn("[Watchdog] Cancellation mid-flight for orderId={} — refunding ref={}", orderId[0], ref);
                    // Refund call happens outside this TX (below)
                    return "REFUND";
                }
                fresh.setStatus(PaymentStatus.SUCCESS);
                fresh.setPaymentReference(ref);
                fresh.setNextRetryAt(null);
                paymentRepository.save(fresh);
                log.info("[Watchdog] Payment SUCCESS orderId={} ref={}", orderId[0], ref);
                publisher.publishPaymentSucceededDirect(traceId[0], sagaId[0], orderId[0], userId[0], amount[0], ref);
                return "OK";
            });

            // Refund if cancel came in while gateway was in flight
            Payment afterCharge = paymentRepository.findById(paymentId).orElse(null);
            if (afterCharge != null && afterCharge.getStatus() == PaymentStatus.REFUNDED
                    && afterCharge.getPaymentReference() != null
                    && afterCharge.getPaymentReference().equals(result.reference())) {
                gateway.refund(orderId[0], ref);
                publisher.publishPaymentRefundedDirect(
                        traceId[0], sagaId[0], orderId[0], userId[0],
                        amount[0], ref, "CANCELLATION_REQUESTED_MID_FLIGHT");
            }

        } catch (ExternalPaymentGateway.PaymentGatewayException ex) {
            // ── Step 3b: gateway failed — schedule next attempt or give up ────
            log.warn("[Watchdog] Charge failed orderId={} attempt={}: {}", orderId[0], attempt[0], ex.getMessage());

            txTemplate.execute(txStatus -> {
                Payment fresh = paymentRepository.findById(paymentId).orElse(null);
                if (fresh == null || fresh.getStatus() != PaymentStatus.PENDING) return null;

                if (attempt[0] >= MAX_ATTEMPTS) {
                    markFailed(fresh, ex.getMessage());
                } else {
                    // Schedule next retry with exponential backoff
                    long backoffSec = BACKOFF_S[attempt[0] - 1];
                    fresh.setNextRetryAt(Instant.now().plusSeconds(backoffSec));
                    paymentRepository.save(fresh);
                    log.info("[Watchdog] Retry scheduled for orderId={} in {}s (attempt {}/{})",
                            orderId[0], backoffSec, attempt[0], MAX_ATTEMPTS);
                }
                return null;
            });
        }
    }

    // ── Command: cancel / refund ─────────────────────────────────────────────

    /**
     * Handles a {@link PaymentCancelCommand} from the saga orchestrator.
     * Idempotent: a second cancel on an already-REFUNDED or FAILED record is a no-op.
     */
    @CacheEvict(value = "payments", key = "#cmd.orderId")
    public void cancelPayment(PaymentCancelCommand cmd) {
        log.warn("[PaymentService] Cancel requested for orderId={} sagaId={} ref={}",
                cmd.getOrderId(), cmd.getSagaId(), cmd.getPaymentReference());

        final String[]         refToRefund = {null};
        final BigDecimal[]     refAmount   = {null};
        final String[]         refUserId   = {null};

        txTemplate.execute(txStatus -> {
            Payment payment = paymentRepository.findByOrderId(cmd.getOrderId()).orElse(null);
            if (payment == null) {
                log.warn("[PaymentService] Cancel: no payment record for orderId={}", cmd.getOrderId());
                return null;
            }

            switch (payment.getStatus()) {
                case PENDING -> {
                    // Watchdog hasn't attempted yet — mark so it skips the gateway call
                    log.info("[PaymentService] Cancel: marking CANCELLATION_REQUESTED orderId={}", cmd.getOrderId());
                    payment.setStatus(PaymentStatus.CANCELLATION_REQUESTED);
                    payment.setNextRetryAt(null);
                    paymentRepository.save(payment);
                    redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());
                }
                case SUCCESS -> {
                    // Already charged — refund immediately
                    log.warn("[PaymentService] Cancel: refunding SUCCESS payment orderId={} ref={}",
                            cmd.getOrderId(), payment.getPaymentReference());
                    refToRefund[0] = payment.getPaymentReference();
                    refAmount[0]   = payment.getAmount();
                    refUserId[0]   = payment.getUserId();
                    payment.setStatus(PaymentStatus.REFUNDED);
                    payment.setNextRetryAt(null);
                    paymentRepository.save(payment);
                    redisTemplate.delete(REDIS_PREFIX + cmd.getOrderId());
                }
                case CANCELLATION_REQUESTED, REFUNDED ->
                    log.info("[PaymentService] Cancel: already in progress/done orderId={} status={}",
                            cmd.getOrderId(), payment.getStatus());
                case FAILED ->
                    log.info("[PaymentService] Cancel: no-op for orderId={} (already failed)", cmd.getOrderId());
            }
            return null;
        });

        // Refund call outside TX — no DB connection held during network I/O
        if (refToRefund[0] != null) {
            gateway.refund(cmd.getOrderId(), refToRefund[0]);
            publisher.publishPaymentRefundedDirect(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getOrderId(), refUserId[0],
                    refAmount[0], refToRefund[0], "SAGA_CANCEL");
        }
    }

    // ── Read path ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "payments", key = "#orderId")
    public PaymentResponse getPaymentByOrderId(String orderId) {
        String cached = redisTemplate.opsForValue().get(REDIS_PREFIX + orderId);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, PaymentResponse.class);
            } catch (Exception e) {
                log.warn("[PaymentService] Redis cache deser failed for orderId={}", orderId);
            }
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new com.ticketing.payment.exception.PaymentNotFoundException(orderId));
        PaymentResponse response = mapper.toResponse(payment);
        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + orderId,
                    objectMapper.writeValueAsString(response), REDIS_TTL);
        } catch (Exception e) {
            log.warn("[PaymentService] Failed to cache payment for orderId={}", orderId);
        }
        return response;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Marks the payment FAILED, clears nextRetryAt, publishes failure events.
     * Must be called inside a transaction.
     */
    private void markFailed(Payment payment, String reason) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        payment.setNextRetryAt(null);
        payment.setAttemptCount(MAX_ATTEMPTS);
        paymentRepository.save(payment);
        redisTemplate.delete(REDIS_PREFIX + payment.getOrderId());

        publisher.publishPaymentFailedDirect(
                payment.getTraceId(), payment.getSagaId(),
                payment.getOrderId(), payment.getUserId(),
                reason, MAX_ATTEMPTS);
        publisher.publishPaymentDlqDirect(
                payment.getTraceId(), payment.getSagaId(),
                payment.getOrderId(), payment.getUserId(),
                reason, MAX_ATTEMPTS);
        publisher.publishAdminNotificationDirect(
                payment.getTraceId(), payment.getSagaId(),
                payment.getOrderId(), payment.getUserId(),
                reason);

        log.error("[PaymentService] Payment FAILED after {} attempts orderId={}",
                MAX_ATTEMPTS, payment.getOrderId());
    }
}
