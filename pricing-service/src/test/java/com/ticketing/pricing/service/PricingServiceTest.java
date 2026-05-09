package com.ticketing.pricing.service;

import com.ticketing.common.events.*;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PricingService — lockPrice()")
class PricingServiceTest {

    @Mock EventPriceRuleRepository  ruleRepository;
    @Mock PriceHistoryRepository    historyRepository;
    @Mock PricingEventPublisher     publisher;
    @Mock PriceRuleMapper           mapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;

    @InjectMocks PricingService pricingService;

    // shared fixtures
    private static final String EVENT_ID  = "event-001";
    private static final String TICKET_ID = "ticket-001";
    private static final String ORDER_ID  = "order-001";
    private static final String SAGA_ID   = "saga-001";
    private static final String TRACE_ID  = "trace-001";

    private EventPriceRule rule;

    // facePrice used in all commands — effective price = facePrice * surgeMultiplier
    private static final BigDecimal FACE_PRICE = new BigDecimal("100.00");

    @BeforeEach
    void setUp() {
        // surgeMultiplier=1.2 → effectivePrice = 100 * 1.2 = 120.00
        rule = EventPriceRule.builder()
                .eventId(EVENT_ID)
                .surgeMultiplier(new BigDecimal("1.2"))
                .maxSurge(new BigDecimal("2.0"))
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case A — fabricated price (never appeared in history)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case A — fabricated price")
    class FabricatedPrice {

        @Test
        @DisplayName("publishes PricingFailedEvent with INVALID_PRICE reason")
        void fabricated_price_publishes_failed_event() {
            // userPrice=1.00, facePrice=100 → claimedMultiplier=0.01, never in history
            BigDecimal fakePrice = new BigDecimal("1.00");
            PriceLockCommand cmd = buildCmd(fakePrice, false);

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));
            when(historyRepository.findMultiplierAt(eq(EVENT_ID), any()))
                    .thenReturn(new BigDecimal("1.2")); // current multiplier at order time
            BigDecimal claimedMultiplier = fakePrice.divide(FACE_PRICE, 4, java.math.RoundingMode.HALF_UP);
            when(historyRepository.existsInRecentHistory(eq(EVENT_ID), eq(claimedMultiplier), any()))
                    .thenReturn(false); // never existed

            pricingService.lockPrice(cmd);

            ArgumentCaptor<PricingFailedEvent> captor = ArgumentCaptor.forClass(PricingFailedEvent.class);
            verify(publisher).publishPricingFailed(captor.capture());
            assertThat(captor.getValue().getReason()).isEqualTo("INVALID_PRICE");
            assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);

            // must NOT lock
            verify(publisher, never()).publishPricingLocked(any());
        }

        @Test
        @DisplayName("does not write Redis lock for fabricated price")
        void fabricated_price_does_not_write_redis() {
            PriceLockCommand cmd = buildCmd(new BigDecimal("0.01"), false);

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));
            when(historyRepository.findMultiplierAt(any(), any())).thenReturn(new BigDecimal("1.2"));
            when(historyRepository.existsInRecentHistory(any(), any(), any())).thenReturn(false);

            pricingService.lockPrice(cmd);

            verify(valueOps, never()).set(any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case B — price matches at order creation time
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case B — price matches")
    class PriceMatches {

        @Test
        @DisplayName("publishes PricingLockedEvent with the matched price")
        void matching_price_locks_and_publishes() {
            // userPrice=120, facePrice=100 → claimedMultiplier=1.2 → matches history
            BigDecimal correctPrice = new BigDecimal("120.00");
            BigDecimal multiplier = new BigDecimal("1.2");
            PriceLockCommand cmd = buildCmd(correctPrice, false);

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));
            when(historyRepository.findMultiplierAt(eq(EVENT_ID), any())).thenReturn(multiplier);
            BigDecimal claimedMultiplier = correctPrice.divide(FACE_PRICE, 4, java.math.RoundingMode.HALF_UP);
            when(historyRepository.existsInRecentHistory(eq(EVENT_ID), eq(claimedMultiplier), any()))
                    .thenReturn(true);

            pricingService.lockPrice(cmd);

            ArgumentCaptor<PricingLockedEvent> captor = ArgumentCaptor.forClass(PricingLockedEvent.class);
            verify(publisher).publishPricingLocked(captor.capture());
            assertThat(captor.getValue().getLockedPrice()).isEqualByComparingTo(correctPrice);
            assertThat(captor.getValue().getSagaId()).isEqualTo(SAGA_ID);

            verify(publisher, never()).publishPriceChanged(any());
            verify(publisher, never()).publishPricingFailed(any());
        }

        @Test
        @DisplayName("writes price to Redis lock key")
        void matching_price_writes_redis() {
            BigDecimal correctPrice = new BigDecimal("120.00");
            PriceLockCommand cmd = buildCmd(correctPrice, false);

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));
            when(historyRepository.findMultiplierAt(any(), any())).thenReturn(new BigDecimal("1.2"));
            when(historyRepository.existsInRecentHistory(any(), any(), any())).thenReturn(true);

            pricingService.lockPrice(cmd);

            verify(valueOps).set(
                    contains(TICKET_ID),
                    eq(correctPrice.toPlainString()),
                    any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Case C — price was real but has changed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case C — price changed")
    class PriceChanged {

        @Test
        @DisplayName("publishes PriceChangedEvent with old and new prices")
        void stale_price_publishes_price_changed() {
            // User paid at multiplier 0.99 (price=99), current is 1.2 (price=120)
            BigDecimal oldPrice = new BigDecimal("99.00");     // userPrice
            BigDecimal oldMultiplier = new BigDecimal("0.9900"); // userPrice/facePrice
            BigDecimal currentMultiplier = new BigDecimal("1.2");
            BigDecimal newPrice = new BigDecimal("120.00");    // facePrice * currentMultiplier
            PriceLockCommand cmd = buildCmd(oldPrice, false);

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));
            // At order time the multiplier was already 1.2 (price changed since user saw 0.99)
            when(historyRepository.findMultiplierAt(eq(EVENT_ID), any()))
                    .thenReturn(currentMultiplier);
            when(historyRepository.existsInRecentHistory(eq(EVENT_ID), eq(oldMultiplier), any()))
                    .thenReturn(true); // oldMultiplier did exist in history

            pricingService.lockPrice(cmd);

            ArgumentCaptor<PriceChangedEvent> captor = ArgumentCaptor.forClass(PriceChangedEvent.class);
            verify(publisher).publishPriceChanged(captor.capture());
            assertThat(captor.getValue().getOldPrice()).isEqualByComparingTo(oldPrice);
            assertThat(captor.getValue().getNewPrice()).isEqualByComparingTo(newPrice);
            assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);
            assertThat(captor.getValue().getConfirmExpiresAt()).isAfter(Instant.now());

            verify(publisher, never()).publishPricingLocked(any());
            verify(publisher, never()).publishPricingFailed(any());
        }

        @Test
        @DisplayName("does not write Redis lock when price changed")
        void stale_price_does_not_write_redis() {
            BigDecimal oldPrice = new BigDecimal("99.00");
            PriceLockCommand cmd = buildCmd(oldPrice, false);

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));
            when(historyRepository.findMultiplierAt(any(), any())).thenReturn(new BigDecimal("1.2"));
            when(historyRepository.existsInRecentHistory(any(), any(), any())).thenReturn(true);

            pricingService.lockPrice(cmd);

            verify(valueOps, never()).set(any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirmed re-lock — user already accepted price change
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Confirmed re-lock")
    class ConfirmedLock {

        @Test
        @DisplayName("skips validation and locks at current price when confirmed=true")
        void confirmed_lock_skips_validation() {
            // facePrice=100, surgeMultiplier=1.2 → expectedLockedPrice=120
            BigDecimal currentPrice = new BigDecimal("120.00");
            PriceLockCommand cmd = buildCmd(currentPrice, true); // confirmed = true

            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(rule));

            pricingService.lockPrice(cmd);

            // no history queries at all
            verify(historyRepository, never()).findMultiplierAt(any(), any());
            verify(historyRepository, never()).existsInRecentHistory(any(), any(), any());

            // locks immediately at current price
            ArgumentCaptor<PricingLockedEvent> captor = ArgumentCaptor.forClass(PricingLockedEvent.class);
            verify(publisher).publishPricingLocked(captor.capture());
            assertThat(captor.getValue().getLockedPrice()).isEqualByComparingTo(currentPrice);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No price rule found
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("No price rule")
    class NoPriceRule {

        @Test
        @DisplayName("publishes PricingFailedEvent with NO_PRICE_RULE reason")
        void no_rule_publishes_failed() {
            PriceLockCommand cmd = buildCmd(new BigDecimal("99.00"), false);
            when(ruleRepository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());

            pricingService.lockPrice(cmd);

            ArgumentCaptor<PricingFailedEvent> captor = ArgumentCaptor.forClass(PricingFailedEvent.class);
            verify(publisher).publishPricingFailed(captor.capture());
            assertThat(captor.getValue().getReason()).isEqualTo("NO_PRICE_RULE");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private PriceLockCommand buildCmd(BigDecimal userPrice, boolean confirmed) {
        return new PriceLockCommand(
                TRACE_ID, SAGA_ID, TICKET_ID, ORDER_ID, EVENT_ID,
                userPrice, FACE_PRICE, Instant.now().minusSeconds(30), confirmed);
    }
}
