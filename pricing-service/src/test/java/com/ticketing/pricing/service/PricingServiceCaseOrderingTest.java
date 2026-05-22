package com.ticketing.pricing.service;

import com.ticketing.common.events.PriceChangedEvent;
import com.ticketing.common.events.PriceLockCommand;
import com.ticketing.common.events.PricingFailedEvent;
import com.ticketing.common.events.PricingLockedEvent;
import com.ticketing.common.exception.ErrorCode;
import com.ticketing.pricing.client.TicketValidationClient;
import com.ticketing.pricing.domain.model.EventPriceRule;
import com.ticketing.pricing.domain.repository.EventPriceRuleRepository;
import com.ticketing.pricing.domain.repository.PriceHistoryRepository;
import com.ticketing.pricing.kafka.PricingEventPublisher;
import com.ticketing.pricing.mapper.PriceRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Q5 fix in {@link PricingService#lockPrice}.
 *
 * <p>The fix reordered the three branches inside {@code lockPrice()}:
 * <pre>
 *   BEFORE:  Case A (fabricated check) → Case B (exact match) → Case C (stale)
 *   AFTER:   Case B (exact match) → Case A → Case C
 * </pre>
 *
 * <p>Why the reorder matters: when a surge multiplier is stable for longer than
 * {@code HISTORY_VALIDITY_WINDOW}, the history-validity check returns false
 * because no recent rows match. The old order rejected legitimate orders as
 * "fabricated" even though the user's price exactly matched the current effective
 * price. The new order short-circuits on exact-match before consulting the
 * history-validity window.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PricingService — Case A/B/C branch ordering")
class PricingServiceCaseOrderingTest {

    @Mock EventPriceRuleRepository repository;
    @Mock PriceHistoryRepository   priceHistoryRepository;
    @Mock PriceRuleMapper          mapper;
    @Mock PricingEventPublisher    publisher;
    @Mock TicketValidationClient   ticketValidationClient;

    private PricingService pricingService;

    private static final String EVENT_ID  = "event-001";
    private static final String TICKET_ID = "ticket-001";
    private static final String ORDER_ID  = "order-001";
    private static final String SAGA_ID   = "saga-001";
    private static final String TRACE_ID  = "trace-001";

    private static final BigDecimal FACE_PRICE = new BigDecimal("100.00");

    @BeforeEach
    void setUp() {
        pricingService = new PricingService(
                repository, priceHistoryRepository, mapper, publisher, ticketValidationClient);
    }

    private EventPriceRule rule(BigDecimal currentMultiplier) {
        EventPriceRule r = new EventPriceRule();
        r.setEventId(EVENT_ID);
        r.setSurgeMultiplier(currentMultiplier);
        r.setMaxSurge(new BigDecimal("2.0"));
        return r;
    }

    private PriceLockCommand cmd(BigDecimal userPrice) {
        return new PriceLockCommand(
                TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, EVENT_ID,
                userPrice, FACE_PRICE,
                Instant.now().minus(java.time.Duration.ofMinutes(5)),  // orderCreatedAt
                false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // THE FIX — Case B (exact match) MUST short-circuit before Case A is even checked
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case B short-circuit")
    class CaseBShortCircuit {

        @Test
        @DisplayName("exact match locks even when multiplier is too old to pass validity window")
        void exact_match_locks_even_when_history_is_stale() {
            // Current multiplier = 1.0× → expected price = 100.00 (matches face price)
            when(repository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.of(rule(BigDecimal.ONE)));
            // multiplierAtOrderTime is 1.0
            when(priceHistoryRepository.findMultiplierAt(eq(EVENT_ID), any(Instant.class)))
                    .thenReturn(BigDecimal.ONE);
            // IMPORTANT: simulate "history-validity window has lapsed" — multiplier
            // has been stable for longer than the window. Old code: this rejects
            // as fabricated. New code: Case B fires first, this never matters.
            when(priceHistoryRepository.existsInRecentHistory(eq(EVENT_ID), any(BigDecimal.class), any(Instant.class)))
                    .thenReturn(false);

            // User submits the correct current price (1.0 × 100.00 = 100.00)
            pricingService.lockPrice(cmd(new BigDecimal("100.00")));

            // ── THE CRITICAL ASSERTION ──
            // Must lock at the exact price — even though the "validity window" check
            // would have rejected this as fabricated under the old code ordering.
            ArgumentCaptor<PricingLockedEvent> evt = ArgumentCaptor.forClass(PricingLockedEvent.class);
            verify(publisher).publishPricingLocked(evt.capture());
            assertThat(evt.getValue().getLockedPrice()).isEqualByComparingTo(new BigDecimal("100.00"));

            // Must NOT publish a Failed event with INVALID_PRICE
            verify(publisher, never()).publishPricingFailed(any(PricingFailedEvent.class));
            // Must NOT publish a PriceChanged event
            verify(publisher, never()).publishPriceChanged(any(PriceChangedEvent.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case C — multiplier moved, user's claimed multiplier is recent → renegotiate
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case C — stale-quote renegotiation")
    class CaseCRenegotiation {

        @Test
        @DisplayName("user price implies a multiplier that existed recently → PriceChangedEvent")
        void stale_price_with_recent_multiplier_triggers_renegotiation() {
            // Current multiplier 1.5× → current expected price 150.00
            when(repository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.of(rule(new BigDecimal("1.5"))));
            // At orderCreatedAt, multiplier was also 1.5 (saga's snapshot for point-in-time check)
            when(priceHistoryRepository.findMultiplierAt(eq(EVENT_ID), any(Instant.class)))
                    .thenReturn(new BigDecimal("1.5"));
            // claimedMultiplier (userPrice / facePrice) = 100/100 = 1.0; multiplier 1.0
            // was active recently → existsInRecentHistory returns TRUE → Case C
            when(priceHistoryRepository.existsInRecentHistory(eq(EVENT_ID), any(BigDecimal.class), any(Instant.class)))
                    .thenReturn(true);

            pricingService.lockPrice(cmd(new BigDecimal("100.00")));

            ArgumentCaptor<PriceChangedEvent> evt = ArgumentCaptor.forClass(PriceChangedEvent.class);
            verify(publisher).publishPriceChanged(evt.capture());
            // newPrice the user must confirm = facePrice × current multiplier = 100 × 1.5
            assertThat(evt.getValue().getNewPrice()).isEqualByComparingTo(new BigDecimal("150.00"));

            verify(publisher, never()).publishPricingLocked(any(PricingLockedEvent.class));
            verify(publisher, never()).publishPricingFailed(any(PricingFailedEvent.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case A — fabricated price (claimed multiplier never existed in recent history)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case A — fabricated price")
    class CaseAFabricated {

        @Test
        @DisplayName("user price implies multiplier that never existed → PricingFailedEvent(INVALID_PRICE)")
        void fabricated_price_publishes_failed_event() {
            when(repository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.of(rule(new BigDecimal("1.5"))));
            when(priceHistoryRepository.findMultiplierAt(eq(EVENT_ID), any(Instant.class)))
                    .thenReturn(new BigDecimal("1.5"));
            // claimedMultiplier 0.7 (userPrice 70 / face 100) — never existed
            when(priceHistoryRepository.existsInRecentHistory(eq(EVENT_ID), any(BigDecimal.class), any(Instant.class)))
                    .thenReturn(false);

            pricingService.lockPrice(cmd(new BigDecimal("70.00")));

            ArgumentCaptor<PricingFailedEvent> evt = ArgumentCaptor.forClass(PricingFailedEvent.class);
            verify(publisher).publishPricingFailed(evt.capture());
            assertThat(evt.getValue().getReason()).isEqualTo(ErrorCode.INVALID_PRICE.name());

            verify(publisher, never()).publishPricingLocked(any(PricingLockedEvent.class));
            verify(publisher, never()).publishPriceChanged(any(PriceChangedEvent.class));
        }
    }
}
