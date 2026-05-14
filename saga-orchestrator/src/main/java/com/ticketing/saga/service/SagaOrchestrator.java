package com.ticketing.saga.service;

import com.ticketing.common.events.*;
import com.ticketing.common.exception.ErrorCode;
import com.ticketing.saga.kafka.SagaCommandPublisher;
import com.ticketing.saga.model.SagaState;
import com.ticketing.saga.model.SagaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SagaOrchestrator {

    private static final Logger   log             = LoggerFactory.getLogger(SagaOrchestrator.class);
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);
    /** How long a user has to confirm a price change before the watchdog cancels the saga. */
    private static final Duration PRICE_CONFIRM_TIMEOUT = Duration.ofSeconds(30);

    private final SagaStateStore       stateStore;
    private final SagaCommandPublisher publisher;

    public SagaOrchestrator(SagaStateStore stateStore, SagaCommandPublisher publisher) {
        this.stateStore = stateStore;
        this.publisher  = publisher;
    }

    // -------------------------------------------------------------------------
    // Step 1: order.created -> start saga, send ticket.reserve.cmd
    // -------------------------------------------------------------------------

    public void startSaga(OrderCreatedEvent event) {
        String sagaId = event.getSagaId() != null ? event.getSagaId() : UUID.randomUUID().toString();

        // Idempotency guard — Kafka at-least-once can redeliver the same event.
        // Overwriting an in-progress saga would reset it back to STARTED.
        if (stateStore.load(sagaId) != null) {
            log.warn("Duplicate OrderCreatedEvent for sagaId={}, ignoring", sagaId);
            return;
        }

        log.info("Starting saga: sagaId={} orderId={} userId={} ticketId={} userPrice={}",
                sagaId, event.getOrderId(), event.getUserId(),
                event.getTicketId(), event.getUserPrice());

        SagaState state = new SagaState();
        state.setSagaId(sagaId);
        state.setOrderId(event.getOrderId());
        state.setUserId(event.getUserId());
        state.setTicketId(event.getTicketId());
        state.setUserPrice(event.getUserPrice());
        state.setOrderCreatedAt(event.getOrderCreatedAt());
        state.setStatus(SagaStatus.STARTED);
        state.setCurrentStep("RESERVE_TICKET");
        state.setStartedAt(Instant.now());
        state.setLastUpdatedAt(Instant.now());

        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> STARTED", sagaId);

        publisher.sendTicketReserveCommand(
                event.getTraceId(), sagaId,
                event.getTicketId(), event.getOrderId(), event.getUserId());
    }

    // -------------------------------------------------------------------------
    // Step 2: ticket.reserved -> send pricing.lock.cmd
    // -------------------------------------------------------------------------

    public void onTicketReserved(TicketReservedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.STARTED, sagaId)) return;

        log.info("Saga step: sagaId={} ticketReserved -> sending price lock", sagaId);
        state.setLockedPrice(event.getLockedPrice());   // facePrice from ticket
        state.setEventId(event.getEventId());           // resolved from ticket
        state.setStatus(SagaStatus.TICKET_RESERVED);
        state.setCurrentStep("LOCK_PRICE");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> TICKET_RESERVED", sagaId);

        publisher.sendPriceLockCommand(
                event.getTraceId(), sagaId,
                state.getTicketId(), state.getOrderId(), state.getEventId(),
                state.getUserPrice(), state.getLockedPrice(),
                state.getOrderCreatedAt(), false);
    }

    // -------------------------------------------------------------------------
    // Step 3a: pricing.locked -> send payment.charge.cmd
    // -------------------------------------------------------------------------

    public void onPricingLocked(PricingLockedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        // Accept from TICKET_RESERVED (normal) or AWAITING_PRICE_CONFIRMATION (user confirmed)
        if (state.getStatus() != SagaStatus.TICKET_RESERVED
                && state.getStatus() != SagaStatus.AWAITING_PRICE_CONFIRMATION) {
            log.warn("Unexpected saga status: sagaId={} expected TICKET_RESERVED or AWAITING_PRICE_CONFIRMATION actual={}",
                    sagaId, state.getStatus());
            return;
        }

        log.info("Saga step: sagaId={} pricingLocked lockedPrice={}", sagaId, event.getLockedPrice());
        state.setLockedPrice(event.getLockedPrice());
        state.setPendingPrice(null);
        state.setStatus(SagaStatus.PRICING_LOCKED);
        state.setCurrentStep("CHARGE_PAYMENT");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> PRICING_LOCKED", sagaId);

        publisher.sendPaymentChargeCommand(
                event.getTraceId(), sagaId,
                state.getOrderId(), state.getUserId(),
                state.getTicketId(), event.getLockedPrice());
    }

    // -------------------------------------------------------------------------
    // Step 3b: pricing.price.changed -> pause saga, notify user
    // -------------------------------------------------------------------------

    public void onPriceChanged(PriceChangedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.TICKET_RESERVED, sagaId)) return;

        log.info("Saga price changed: sagaId={} oldPrice={} newPrice={} -> AWAITING_PRICE_CONFIRMATION",
                sagaId, event.getOldPrice(), event.getNewPrice());
        state.setPendingPrice(event.getNewPrice());
        state.setStatus(SagaStatus.AWAITING_PRICE_CONFIRMATION);
        state.setCurrentStep("AWAIT_USER_PRICE_CONFIRM");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> AWAITING_PRICE_CONFIRMATION", sagaId);

        publisher.publishOrderPriceChanged(
                event.getTraceId(), sagaId,
                state.getOrderId(), state.getUserId(),
                event.getOldPrice(), event.getNewPrice(),
                event.getConfirmExpiresAt());
    }

    // -------------------------------------------------------------------------
    // Step 3c: pricing.failed -> INVALID_PRICE = cancel, NO_PRICE_RULE = fail
    // -------------------------------------------------------------------------

    public void onPricingFailed(PricingFailedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        String reason = event.getReason();
        log.warn("Pricing failed: sagaId={} reason={}", sagaId, reason);

        if ("INVALID_PRICE".equals(reason)) {
            // Fabricated price — release ticket and cancel the order
            publisher.sendTicketReleaseCommand(
                    event.getTraceId(), sagaId,
                    state.getTicketId(), state.getOrderId(), ErrorCode.INVALID_PRICE.name());
            state.setStatus(SagaStatus.CANCELLED);
            state.setCurrentStep("CANCELLED");
            state.setFailureReason("Fabricated price rejected");
            state.setLastUpdatedAt(Instant.now());
            stateStore.save(state);
            log.info("Saga state transition: sagaId={} -> CANCELLED (invalid price)", sagaId);
            publisher.publishOrderCancelled(
                    event.getTraceId(), sagaId,
                    state.getOrderId(), state.getUserId(),
                    state.getTicketId(), "Price not found in valid price history");
        } else {
            // System error
            state.setStatus(SagaStatus.COMPENSATING);
            state.setCurrentStep("COMPENSATING");
            state.setLastUpdatedAt(Instant.now());
            stateStore.save(state);
            compensateSaga(sagaId, "Pricing service error: " + reason);
        }
    }

    // -------------------------------------------------------------------------
    // Step 3d: user confirmed price change -> resend pricing.lock.cmd confirmed
    // -------------------------------------------------------------------------

    public void onPriceConfirmReceived(OrderPriceConfirmCommand cmd) {
        String sagaId = cmd.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.AWAITING_PRICE_CONFIRMATION, sagaId)) return;

        log.info("User confirmed price change: sagaId={} orderId={} pendingPrice={}",
                sagaId, state.getOrderId(), state.getPendingPrice());
        // Keep AWAITING until PricingLockedEvent arrives; update price so it's visible
        state.setUserPrice(state.getPendingPrice());
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);

        publisher.sendPriceLockCommand(
                cmd.getTraceId(), sagaId,
                state.getTicketId(), state.getOrderId(), state.getEventId(),
                state.getPendingPrice(), state.getLockedPrice(),
                state.getOrderCreatedAt(), true);
    }

    // -------------------------------------------------------------------------
    // Step 3e: user rejected price change -> release ticket, cancel saga
    // -------------------------------------------------------------------------

    public void onPriceCancelReceived(OrderPriceCancelCommand cmd) {
        String sagaId = cmd.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.AWAITING_PRICE_CONFIRMATION, sagaId)) return;

        log.info("User rejected price change: sagaId={} orderId={}", sagaId, state.getOrderId());
        state.setStatus(SagaStatus.CANCELLED);
        state.setCurrentStep("CANCELLED");
        state.setFailureReason("User rejected price change");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> CANCELLED (user rejected price)", sagaId);

        publisher.sendTicketReleaseCommand(
                cmd.getTraceId(), sagaId,
                state.getTicketId(), state.getOrderId(), ErrorCode.SAGA_COMPENSATION.name());
        publisher.publishOrderCancelled(
                cmd.getTraceId(), sagaId,
                state.getOrderId(), state.getUserId(),
                state.getTicketId(), "User rejected price change");
    }

    // -------------------------------------------------------------------------
    // Step 4: payment.succeeded -> send ticket.confirm.cmd
    // -------------------------------------------------------------------------

    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        // ── Late success: saga already failed (watchdog or ticket release fired first) ──
        // The ticket was released before this event arrived.  We cannot advance the saga,
        // but we MUST refund the charge — otherwise the customer is billed with no ticket.
        if (state.getStatus() == SagaStatus.FAILED
                || state.getStatus() == SagaStatus.CANCELLED
                || state.getStatus() == SagaStatus.COMPENSATING) {
            log.warn("Late PaymentSucceededEvent for terminal saga sagaId={} status={} — issuing refund",
                    sagaId, state.getStatus());
            publisher.sendPaymentCancelCommand(
                    event.getTraceId(), sagaId,
                    state.getOrderId(), event.getPaymentReference());
            return;
        }

        if (!isExpectedStatus(state, SagaStatus.PRICING_LOCKED, sagaId)) return;

        log.info("Saga step: sagaId={} paymentSucceeded paymentReference={}", sagaId, event.getPaymentReference());
        state.setPaymentReference(event.getPaymentReference());
        state.setStatus(SagaStatus.PAYMENT_CHARGED);
        state.setCurrentStep("CONFIRM_TICKET");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> PAYMENT_CHARGED", sagaId);

        publisher.sendTicketConfirmCommand(
                event.getTraceId(), sagaId,
                state.getTicketId(), state.getOrderId());
    }

    // -------------------------------------------------------------------------
    // Step 5: ticket.confirmed -> publish order.confirmed, mark COMPLETED
    // -------------------------------------------------------------------------

    public void onTicketConfirmed(TicketConfirmedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.PAYMENT_CHARGED, sagaId)) return;

        log.info("Saga step: sagaId={} ticketConfirmed -> completing saga", sagaId);
        state.setStatus(SagaStatus.COMPLETED);
        state.setCurrentStep("COMPLETED");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> COMPLETED", sagaId);

        publisher.publishOrderConfirmed(
                event.getTraceId(), sagaId,
                state.getOrderId(), state.getUserId(),
                state.getTicketId(), state.getLockedPrice(),
                state.getPaymentReference());
    }

    // -------------------------------------------------------------------------
    // Compensation: payment.failed
    // -------------------------------------------------------------------------

    public void onPaymentFailed(PaymentFailedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        log.warn("Saga compensation triggered by payment failure: sagaId={} reason={}",
                sagaId, event.getFailureReason());
        state.setFailureReason(event.getFailureReason());
        state.setStatus(SagaStatus.COMPENSATING);
        state.setCurrentStep("COMPENSATING");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);

        compensateSaga(sagaId, event.getFailureReason());
    }

    // -------------------------------------------------------------------------
    // Compensation: ticket.released during saga
    // -------------------------------------------------------------------------

    public void onTicketReleased(TicketReleasedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        // Ignore terminal statuses — this fires as part of normal compensation
        if (state.getStatus() == SagaStatus.COMPLETED
                || state.getStatus() == SagaStatus.FAILED
                || state.getStatus() == SagaStatus.CANCELLED) {
            log.debug("Ignoring ticket.released for sagaId={} in terminal status={}", sagaId, state.getStatus());
            return;
        }

        log.warn("Ticket released during active saga: sagaId={} status={} reason={}",
                sagaId, state.getStatus(), event.getReason());

        // ── Payment safety net ────────────────────────────────────────────────
        // If payment was already in flight (PRICING_LOCKED) or already charged (PAYMENT_CHARGED),
        // send a cancel/refund command BEFORE marking the saga terminal.
        // Without this, the customer gets charged with no ticket and no refund.
        if (state.getStatus() == SagaStatus.PRICING_LOCKED) {
            // Payment in flight — paymentReference is null; service will set CANCELLATION_REQUESTED
            // and refund when the gateway eventually responds with success.
            log.warn("Saga in PRICING_LOCKED when ticket released — sending payment cancel (in-flight) sagaId={}", sagaId);
            publisher.sendPaymentCancelCommand(
                    event.getTraceId(), sagaId, state.getOrderId(), null);
        } else if (state.getStatus() == SagaStatus.PAYMENT_CHARGED) {
            // Payment already succeeded — send reference for immediate refund.
            log.warn("Saga in PAYMENT_CHARGED when ticket released — sending payment cancel (refund) sagaId={}", sagaId);
            publisher.sendPaymentCancelCommand(
                    event.getTraceId(), sagaId, state.getOrderId(), state.getPaymentReference());
        }

        state.setStatus(SagaStatus.FAILED);
        state.setCurrentStep("FAILED");
        state.setFailureReason("Ticket released: " + event.getReason());
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> FAILED (ticket released)", sagaId);

        publisher.publishOrderFailed(
                event.getTraceId(), sagaId,
                state.getOrderId(), state.getUserId(),
                state.getTicketId(), "Ticket released: " + event.getReason());
    }

    // -------------------------------------------------------------------------
    // Compensate saga: release ticket, cancel payment if in flight, publish OrderFailedEvent
    // -------------------------------------------------------------------------

    public void compensateSaga(String sagaId, String reason) {
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        log.warn("Compensating saga: sagaId={} status={} reason={}", sagaId, state.getStatus(), reason);

        // ── Payment safety net ───────────────────────────────────────────────
        // compensateSaga() is called for sagas stuck in ANY active state — including
        // PRICING_LOCKED (payment in flight) and PAYMENT_CHARGED (payment done, ticket
        // not yet confirmed).  Both require a cancel/refund command.
        if (state.getStatus() == SagaStatus.PRICING_LOCKED) {
            log.warn("compensateSaga: PRICING_LOCKED — sending payment cancel (in-flight) sagaId={}", sagaId);
            publisher.sendPaymentCancelCommand(sagaId, sagaId, state.getOrderId(), null);
        } else if (state.getStatus() == SagaStatus.PAYMENT_CHARGED) {
            log.warn("compensateSaga: PAYMENT_CHARGED — sending payment cancel (refund) sagaId={}", sagaId);
            publisher.sendPaymentCancelCommand(sagaId, sagaId, state.getOrderId(), state.getPaymentReference());
        }

        // ── Ticket release ───────────────────────────────────────────────────
        // Explicit TicketReleaseCommand on ticket.cmd (unified topic, same orderId key)
        // so this command is always consumed AFTER any pending TicketConfirmCommand for
        // the same order — Kafka guarantees ordering within a single partition. Using a
        // separate compensation topic would let the release race past an in-flight
        // confirm and fire a spurious failure.
        if (state.getTicketId() != null) {
            publisher.sendTicketReleaseCommand(
                    sagaId, sagaId,
                    state.getTicketId(), state.getOrderId(), reason);
        }

        publisher.publishOrderFailed(
                sagaId, sagaId,
                state.getOrderId(), state.getUserId(),
                state.getTicketId(), reason);

        state.setStatus(SagaStatus.FAILED);
        state.setCurrentStep("FAILED");
        state.setFailureReason(reason);
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> FAILED (compensated)", sagaId);
    }

    // -------------------------------------------------------------------------
    // Watchdog: scan for stuck sagas every 60 seconds
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelay = 60_000)
    public void watchdogScanForStuckSagas() {
        log.debug("Watchdog: scanning for stuck sagas...");
        Instant now                  = Instant.now();
        Instant stuckThreshold       = now.minus(STUCK_THRESHOLD);
        Instant priceConfirmThreshold = now.minus(PRICE_CONFIRM_TIMEOUT);

        // Use the shorter of the two thresholds so both saga types are captured
        // in a single DB query.  The per-saga status check below separates them.
        //
        // Previously: scanActiveSagas() loaded ALL active sagas, deserialised every
        // JSON blob in Java, then filtered by lastUpdatedAt → O(N) per tick.
        //
        // Now: Postgres applies the updated_at filter via idx_saga_states_active_stale
        // (partial index on active rows).  Only genuinely idle sagas are returned;
        // under normal load the result set is empty and the query is essentially free.
        Instant earlierThreshold = priceConfirmThreshold.isBefore(stuckThreshold)
                ? priceConfirmThreshold : stuckThreshold;

        List<SagaState> staleSagas = stateStore.scanStaleSagas(earlierThreshold);
        int stuckCount = 0;

        for (SagaState state : staleSagas) {
            Instant lastUpdated = state.getLastUpdatedAt();
            if (lastUpdated == null) continue;

            // AWAITING_PRICE_CONFIRMATION uses the shorter price-confirm timeout.
            // All other active statuses use the general stuck-saga threshold.
            if (state.getStatus() == SagaStatus.AWAITING_PRICE_CONFIRMATION) {
                if (!lastUpdated.isBefore(priceConfirmThreshold)) continue; // not yet expired
                log.warn("Watchdog: price confirm timed out for sagaId={}", state.getSagaId());
                try {
                    publisher.sendTicketReleaseCommand(
                            state.getSagaId(), state.getSagaId(),
                            state.getTicketId(), state.getOrderId(),
                            ErrorCode.PRICE_CONFIRMATION_TIMEOUT.name());
                    publisher.publishOrderCancelled(
                            state.getSagaId(), state.getSagaId(),
                            state.getOrderId(), state.getUserId(),
                            state.getTicketId(), "Price confirmation window expired");
                    state.setStatus(SagaStatus.CANCELLED);
                    state.setCurrentStep("CANCELLED");
                    state.setFailureReason("Price confirmation timed out");
                    state.setLastUpdatedAt(Instant.now());
                    stateStore.save(state);
                    stuckCount++;
                } catch (Exception e) {
                    log.error("Watchdog failed to cancel price-timeout saga sagaId={}: {}",
                            state.getSagaId(), e.getMessage(), e);
                }
            } else {
                if (!lastUpdated.isBefore(stuckThreshold)) continue; // within normal budget
                log.warn("Watchdog detected stuck saga: sagaId={} status={} lastUpdated={} step={}",
                        state.getSagaId(), state.getStatus(),
                        state.getLastUpdatedAt(), state.getCurrentStep());
                try {
                    state.setStatus(SagaStatus.COMPENSATING);
                    state.setLastUpdatedAt(Instant.now());
                    stateStore.save(state);
                    compensateSaga(state.getSagaId(), ErrorCode.SAGA_STUCK.name());
                    stuckCount++;
                } catch (Exception e) {
                    log.error("Watchdog failed to compensate stuck saga sagaId={}: {}",
                            state.getSagaId(), e.getMessage(), e);
                }
            }
        }

        if (stuckCount > 0) {
            log.warn("Watchdog: handled {} stuck/timed-out saga(s)", stuckCount);
        } else {
            log.debug("Watchdog: no stuck sagas found (scanned {} stale candidates)", staleSagas.size());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SagaState loadOrWarn(String sagaId) {
        if (sagaId == null) {
            log.warn("Received event with null sagaId, ignoring");
            return null;
        }
        SagaState state = stateStore.load(sagaId);
        if (state == null) {
            log.warn("No saga state found for sagaId={}, ignoring event", sagaId);
        }
        return state;
    }

    private boolean isExpectedStatus(SagaState state, SagaStatus expected, String sagaId) {
        if (state.getStatus() != expected) {
            log.warn("Unexpected saga status: sagaId={} expected={} actual={}. Ignoring event.",
                    sagaId, expected, state.getStatus());
            return false;
        }
        return true;
    }
}
