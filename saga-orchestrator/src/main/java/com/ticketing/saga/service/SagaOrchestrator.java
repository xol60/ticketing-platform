package com.ticketing.saga.service;

import com.ticketing.common.events.*;
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

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);

    private final SagaStateStore stateStore;
    private final SagaCommandPublisher publisher;

    public SagaOrchestrator(SagaStateStore stateStore, SagaCommandPublisher publisher) {
        this.stateStore = stateStore;
        this.publisher = publisher;
    }

    // -------------------------------------------------------------------------
    // Step 1: order.created -> start saga, send ticket.reserve.cmd
    // -------------------------------------------------------------------------

    public void startSaga(OrderCreatedEvent event) {
        String sagaId = event.getSagaId() != null ? event.getSagaId() : UUID.randomUUID().toString();
        log.info("Starting saga: sagaId={}, orderId={}, userId={}, ticketId={}",
                sagaId, event.getOrderId(), event.getUserId(), event.getTicketId());

        SagaState state = new SagaState();
        state.setSagaId(sagaId);
        state.setOrderId(event.getOrderId());
        state.setUserId(event.getUserId());
        state.setTicketId(event.getTicketId());
        state.setLockedPrice(event.getRequestedPrice());
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

        log.info("Saga step: sagaId={}, ticketReserved, lockedPrice={}", sagaId, event.getLockedPrice());
        state.setLockedPrice(event.getLockedPrice());
        state.setStatus(SagaStatus.TICKET_RESERVED);
        state.setCurrentStep("LOCK_PRICE");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> TICKET_RESERVED", sagaId);

        publisher.sendPriceLockCommand(
                event.getTraceId(), sagaId,
                state.getTicketId(), state.getOrderId(),
                state.getEventId(), event.getLockedPrice());
    }

    // -------------------------------------------------------------------------
    // Step 3: pricing.locked -> send payment.charge.cmd
    // -------------------------------------------------------------------------

    public void onPricingLocked(PricingLockedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.TICKET_RESERVED, sagaId)) return;

        log.info("Saga step: sagaId={}, pricingLocked, lockedPrice={}", sagaId, event.getLockedPrice());
        state.setLockedPrice(event.getLockedPrice());
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
    // Step 4: payment.succeeded -> send ticket.confirm.cmd
    // -------------------------------------------------------------------------

    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        if (!isExpectedStatus(state, SagaStatus.PRICING_LOCKED, sagaId)) return;

        log.info("Saga step: sagaId={}, paymentSucceeded, paymentReference={}", sagaId, event.getPaymentReference());
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

        log.info("Saga step: sagaId={}, ticketConfirmed -> completing saga", sagaId);
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

        log.warn("Saga compensation triggered by payment failure: sagaId={}, reason={}",
                sagaId, event.getFailureReason());
        state.setFailureReason(event.getFailureReason());
        state.setStatus(SagaStatus.COMPENSATING);
        state.setCurrentStep("COMPENSATING");
        state.setLastUpdatedAt(Instant.now());
        stateStore.save(state);
        log.info("Saga state transition: sagaId={} -> COMPENSATING (payment failed)", sagaId);

        compensateSaga(sagaId, event.getFailureReason());
    }

    // -------------------------------------------------------------------------
    // Compensation: ticket.released during saga -> unlock price if locked
    // -------------------------------------------------------------------------

    public void onTicketReleased(TicketReleasedEvent event) {
        String sagaId = event.getSagaId();
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        // Only act if saga is active (not already COMPLETED or FAILED)
        if (state.getStatus() == SagaStatus.COMPLETED || state.getStatus() == SagaStatus.FAILED) {
            log.debug("Ignoring ticket.released for sagaId={} in terminal status={}", sagaId, state.getStatus());
            return;
        }

        log.warn("Ticket released during saga: sagaId={}, reason={}", sagaId, event.getReason());

        // If pricing was locked, send unlock command
        if (state.getStatus() == SagaStatus.PRICING_LOCKED
                || state.getStatus() == SagaStatus.PAYMENT_CHARGED) {
            publisher.sendPriceUnlockCommand(
                    event.getTraceId(), sagaId,
                    state.getTicketId(), state.getOrderId(),
                    "Ticket released during saga: " + event.getReason());
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
    // Compensate saga: publishes SagaCompensateEvent + OrderFailedEvent
    // -------------------------------------------------------------------------

    public void compensateSaga(String sagaId, String reason) {
        SagaState state = loadOrWarn(sagaId);
        if (state == null) return;

        log.warn("Compensating saga: sagaId={}, reason={}", sagaId, reason);

        publisher.publishSagaCompensate(
                sagaId, sagaId,   // traceId falls back to sagaId if not available
                state.getCurrentStep(),
                state.getOrderId(),
                state.getTicketId(),
                reason);

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
        Instant stuckThreshold = Instant.now().minus(STUCK_THRESHOLD);

        List<SagaState> allSagas = stateStore.scanAllSagas();
        int stuckCount = 0;

        for (SagaState state : allSagas) {
            // Skip terminal statuses
            if (state.getStatus() == SagaStatus.COMPLETED
                    || state.getStatus() == SagaStatus.FAILED) {
                continue;
            }

            if (state.getLastUpdatedAt() != null
                    && state.getLastUpdatedAt().isBefore(stuckThreshold)) {
                log.warn("Watchdog detected stuck saga: sagaId={}, status={}, lastUpdated={}, currentStep={}",
                        state.getSagaId(), state.getStatus(),
                        state.getLastUpdatedAt(), state.getCurrentStep());
                stuckCount++;

                try {
                    state.setStatus(SagaStatus.COMPENSATING);
                    state.setLastUpdatedAt(Instant.now());
                    stateStore.save(state);
                    compensateSaga(state.getSagaId(), "Saga stuck: no progress for >5 minutes");
                } catch (Exception e) {
                    log.error("Watchdog failed to compensate stuck saga sagaId={}: {}",
                            state.getSagaId(), e.getMessage(), e);
                }
            }
        }

        if (stuckCount > 0) {
            log.warn("Watchdog: compensated {} stuck saga(s)", stuckCount);
        } else {
            log.debug("Watchdog: no stuck sagas found (scanned {})", allSagas.size());
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
            log.warn("Unexpected saga status: sagaId={}, expected={}, actual={}. Ignoring event.",
                    sagaId, expected, state.getStatus());
            return false;
        }
        return true;
    }
}
