package com.ticketing.saga.service;

import com.ticketing.common.events.*;
import com.ticketing.saga.kafka.SagaCommandPublisher;
import com.ticketing.saga.model.SagaState;
import com.ticketing.saga.model.SagaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaOrchestrator")
class SagaOrchestratorTest {

    @Mock SagaStateStore       stateStore;
    @Mock SagaCommandPublisher publisher;

    @InjectMocks SagaOrchestrator orchestrator;

    private static final String SAGA_ID   = "saga-001";
    private static final String ORDER_ID  = "order-001";
    private static final String USER_ID   = "user-001";
    private static final String TICKET_ID = "ticket-001";
    private static final String EVENT_ID  = "event-001";
    private static final String TRACE_ID  = "trace-001";

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path — step 1: startSaga
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("startSaga()")
    class StartSaga {

        @Test
        @DisplayName("saves STARTED state and sends TicketReserveCommand")
        void start_saves_state_and_sends_reserve() {
            var event = new OrderCreatedEvent(
                    TRACE_ID, SAGA_ID, ORDER_ID, USER_ID,
                    TICKET_ID, new BigDecimal("99.00"), Instant.now());

            orchestrator.startSaga(event);

            ArgumentCaptor<SagaState> stateCaptor = ArgumentCaptor.forClass(SagaState.class);
            verify(stateStore).save(stateCaptor.capture());
            SagaState saved = stateCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(SagaStatus.STARTED);
            assertThat(saved.getUserPrice()).isEqualByComparingTo("99.00");
            assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);

            verify(publisher).sendTicketReserveCommand(
                    eq(TRACE_ID), eq(SAGA_ID), eq(TICKET_ID), eq(ORDER_ID), eq(USER_ID));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path — step 2: onTicketReserved
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onTicketReserved()")
    class OnTicketReserved {

        @Test
        @DisplayName("advances to TICKET_RESERVED and sends PriceLockCommand")
        void ticket_reserved_advances_and_sends_price_lock() {
            SagaState state = buildState(SagaStatus.STARTED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new TicketReservedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, new BigDecimal("99.00"));

            orchestrator.onTicketReserved(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.TICKET_RESERVED);
            verify(publisher).sendPriceLockCommand(
                    eq(TRACE_ID), eq(SAGA_ID), eq(TICKET_ID), eq(ORDER_ID),
                    any(), any(), any(), eq(false));
        }

        @Test
        @DisplayName("ignores event when saga is not in STARTED status")
        void ignores_wrong_status() {
            SagaState state = buildState(SagaStatus.PRICING_LOCKED); // wrong
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new TicketReservedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, new BigDecimal("99.00"));
            orchestrator.onTicketReserved(event);

            verify(publisher, never()).sendPriceLockCommand(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path — step 3: onPricingLocked
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onPricingLocked()")
    class OnPricingLocked {

        @Test
        @DisplayName("advances to PRICING_LOCKED and sends PaymentChargeCommand")
        void pricing_locked_advances_and_charges() {
            SagaState state = buildState(SagaStatus.TICKET_RESERVED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new PricingLockedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, new BigDecimal("99.00"));
            orchestrator.onPricingLocked(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.PRICING_LOCKED);
            assertThat(state.getLockedPrice()).isEqualByComparingTo("99.00");
            verify(publisher).sendPaymentChargeCommand(
                    eq(TRACE_ID), eq(SAGA_ID), eq(ORDER_ID), eq(USER_ID),
                    eq(TICKET_ID), eq(new BigDecimal("99.00")));
        }

        @Test
        @DisplayName("also accepts AWAITING_PRICE_CONFIRMATION (user confirmed re-lock)")
        void accepts_awaiting_confirmation_status() {
            SagaState state = buildState(SagaStatus.AWAITING_PRICE_CONFIRMATION);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new PricingLockedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, new BigDecimal("120.00"));
            orchestrator.onPricingLocked(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.PRICING_LOCKED);
            assertThat(state.getPendingPrice()).isNull(); // cleared
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Price changed — saga pauses
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onPriceChanged()")
    class OnPriceChanged {

        @Test
        @DisplayName("sets AWAITING_PRICE_CONFIRMATION and notifies order-service")
        void pauses_and_notifies() {
            SagaState state = buildState(SagaStatus.TICKET_RESERVED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new PriceChangedEvent(
                    TRACE_ID, SAGA_ID, ORDER_ID, TICKET_ID,
                    new BigDecimal("99.00"), new BigDecimal("120.00"),
                    Instant.now().plusSeconds(300));

            orchestrator.onPriceChanged(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.AWAITING_PRICE_CONFIRMATION);
            assertThat(state.getPendingPrice()).isEqualByComparingTo("120.00");
            verify(publisher).publishOrderPriceChanged(
                    eq(TRACE_ID), eq(SAGA_ID), eq(ORDER_ID), eq(USER_ID),
                    eq(new BigDecimal("99.00")), eq(new BigDecimal("120.00")), any());
        }

        @Test
        @DisplayName("ignores event when saga is not in TICKET_RESERVED status")
        void ignores_wrong_status() {
            SagaState state = buildState(SagaStatus.PRICING_LOCKED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new PriceChangedEvent(TRACE_ID, SAGA_ID, ORDER_ID, TICKET_ID,
                    new BigDecimal("99.00"), new BigDecimal("120.00"), Instant.now().plusSeconds(300));
            orchestrator.onPriceChanged(event);

            verify(publisher, never()).publishOrderPriceChanged(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pricing failed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onPricingFailed()")
    class OnPricingFailed {

        @Test
        @DisplayName("INVALID_PRICE: releases ticket and publishes OrderCancelledEvent")
        void invalid_price_cancels_saga() {
            SagaState state = buildState(SagaStatus.TICKET_RESERVED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new PricingFailedEvent(TRACE_ID, SAGA_ID, ORDER_ID, TICKET_ID, "INVALID_PRICE");
            orchestrator.onPricingFailed(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.CANCELLED);
            verify(publisher).sendTicketReleaseCommand(
                    eq(TRACE_ID), eq(SAGA_ID), eq(TICKET_ID), eq(ORDER_ID), anyString());
            verify(publisher).publishOrderCancelled(
                    eq(TRACE_ID), eq(SAGA_ID), eq(ORDER_ID), eq(USER_ID), eq(TICKET_ID), anyString());
            verify(publisher, never()).publishOrderFailed(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("NO_PRICE_RULE: triggers compensateSaga (system error)")
        void no_price_rule_compensates() {
            SagaState state = buildState(SagaStatus.TICKET_RESERVED);
            when(stateStore.load(SAGA_ID)).thenReturn(state).thenReturn(state); // load twice (compensateSaga)

            var event = new PricingFailedEvent(TRACE_ID, SAGA_ID, ORDER_ID, TICKET_ID, "NO_PRICE_RULE");
            orchestrator.onPricingFailed(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.FAILED);
            verify(publisher).publishOrderFailed(any(), any(), eq(ORDER_ID), any(), any(), anyString());
            // no ticket release command — compensation flow handles it
            verify(publisher, never()).publishOrderCancelled(any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User confirmed price change
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onPriceConfirmReceived()")
    class OnPriceConfirm {

        @Test
        @DisplayName("resends PriceLockCommand with confirmed=true")
        void confirm_resends_price_lock() {
            SagaState state = buildState(SagaStatus.AWAITING_PRICE_CONFIRMATION);
            state.setPendingPrice(new BigDecimal("120.00"));
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var cmd = new OrderPriceConfirmCommand(TRACE_ID, SAGA_ID, ORDER_ID, USER_ID);
            orchestrator.onPriceConfirmReceived(cmd);

            verify(publisher).sendPriceLockCommand(
                    eq(TRACE_ID), eq(SAGA_ID), eq(TICKET_ID), eq(ORDER_ID),
                    any(), eq(new BigDecimal("120.00")), any(), eq(true)); // confirmed=true
        }

        @Test
        @DisplayName("ignores event when saga is not AWAITING_PRICE_CONFIRMATION")
        void ignores_wrong_status() {
            SagaState state = buildState(SagaStatus.TICKET_RESERVED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var cmd = new OrderPriceConfirmCommand(TRACE_ID, SAGA_ID, ORDER_ID, USER_ID);
            orchestrator.onPriceConfirmReceived(cmd);

            verify(publisher, never()).sendPriceLockCommand(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User rejected price change
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onPriceCancelReceived()")
    class OnPriceCancel {

        @Test
        @DisplayName("releases ticket, cancels saga, publishes OrderCancelledEvent")
        void cancel_releases_and_cancels() {
            SagaState state = buildState(SagaStatus.AWAITING_PRICE_CONFIRMATION);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var cmd = new OrderPriceCancelCommand(TRACE_ID, SAGA_ID, ORDER_ID, USER_ID);
            orchestrator.onPriceCancelReceived(cmd);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.CANCELLED);
            assertThat(state.getFailureReason()).contains("rejected");
            verify(publisher).sendTicketReleaseCommand(
                    eq(TRACE_ID), eq(SAGA_ID), eq(TICKET_ID), eq(ORDER_ID), anyString());
            verify(publisher).publishOrderCancelled(
                    eq(TRACE_ID), eq(SAGA_ID), eq(ORDER_ID), eq(USER_ID), eq(TICKET_ID), anyString());
        }

        @Test
        @DisplayName("ignores event when saga is not AWAITING_PRICE_CONFIRMATION")
        void ignores_wrong_status() {
            SagaState state = buildState(SagaStatus.TICKET_RESERVED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var cmd = new OrderPriceCancelCommand(TRACE_ID, SAGA_ID, ORDER_ID, USER_ID);
            orchestrator.onPriceCancelReceived(cmd);

            verify(publisher, never()).sendTicketReleaseCommand(any(), any(), any(), any(), any());
            verify(publisher, never()).publishOrderCancelled(any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payment failed — compensation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onPaymentFailed()")
    class OnPaymentFailed {

        @Test
        @DisplayName("transitions to FAILED and publishes OrderFailedEvent")
        void payment_failed_compensates() {
            SagaState state = buildState(SagaStatus.PRICING_LOCKED);
            when(stateStore.load(SAGA_ID)).thenReturn(state).thenReturn(state);

            var event = new PaymentFailedEvent(TRACE_ID, SAGA_ID, ORDER_ID, USER_ID, TICKET_ID, "Insufficient funds");
            orchestrator.onPaymentFailed(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.FAILED);
            verify(publisher).publishOrderFailed(any(), any(), eq(ORDER_ID), any(), any(), eq("Insufficient funds"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ticket released during saga
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onTicketReleased()")
    class OnTicketReleased {

        @Test
        @DisplayName("unlocks price when status is PRICING_LOCKED and publishes OrderFailedEvent")
        void releases_price_lock_when_pricing_locked() {
            SagaState state = buildState(SagaStatus.PRICING_LOCKED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new TicketReleasedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, "Admin released");
            orchestrator.onTicketReleased(event);

            assertThat(state.getStatus()).isEqualTo(SagaStatus.FAILED);
            verify(publisher).sendPriceUnlockCommand(any(), any(), any(), any(), anyString());
            verify(publisher).publishOrderFailed(any(), any(), eq(ORDER_ID), any(), any(), anyString());
        }

        @Test
        @DisplayName("ignores ticket.released when saga is already COMPLETED")
        void ignores_completed_saga() {
            SagaState state = buildState(SagaStatus.COMPLETED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new TicketReleasedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, "Admin released");
            orchestrator.onTicketReleased(event);

            verify(publisher, never()).publishOrderFailed(any(), any(), any(), any(), any(), any());
            verify(publisher, never()).sendPriceUnlockCommand(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ignores ticket.released when saga is already CANCELLED")
        void ignores_cancelled_saga() {
            SagaState state = buildState(SagaStatus.CANCELLED);
            when(stateStore.load(SAGA_ID)).thenReturn(state);

            var event = new TicketReleasedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, "User cancelled");
            orchestrator.onTicketReleased(event);

            verify(publisher, never()).publishOrderFailed(any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Saga not found
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Unknown sagaId")
    class UnknownSaga {

        @Test
        @DisplayName("silently ignores TicketReservedEvent when saga state not found")
        void ignores_unknown_saga_on_ticket_reserved() {
            when(stateStore.load(SAGA_ID)).thenReturn(null);

            var event = new TicketReservedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, new BigDecimal("99.00"));
            orchestrator.onTicketReserved(event);

            verify(publisher, never()).sendPriceLockCommand(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("silently ignores PricingLockedEvent when saga state not found")
        void ignores_unknown_saga_on_pricing_locked() {
            when(stateStore.load(SAGA_ID)).thenReturn(null);

            var event = new PricingLockedEvent(TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, new BigDecimal("99.00"));
            orchestrator.onPricingLocked(event);

            verify(publisher, never()).sendPaymentChargeCommand(any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private SagaState buildState(SagaStatus status) {
        SagaState state = new SagaState();
        state.setSagaId(SAGA_ID);
        state.setOrderId(ORDER_ID);
        state.setUserId(USER_ID);
        state.setTicketId(TICKET_ID);
        state.setEventId(EVENT_ID);
        state.setUserPrice(new BigDecimal("99.00"));
        state.setOrderCreatedAt(Instant.now().minusSeconds(60));
        state.setStatus(status);
        state.setCurrentStep(status.name());
        state.setLastUpdatedAt(Instant.now());
        return state;
    }
}
