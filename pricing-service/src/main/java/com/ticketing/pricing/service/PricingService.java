package com.ticketing.pricing.service;

import com.ticketing.common.events.*;
import com.ticketing.common.exception.ErrorCode;
import com.ticketing.pricing.client.TicketValidationClient;
import com.ticketing.pricing.config.CacheConfig;
import com.ticketing.pricing.domain.model.EventPriceRule;
import com.ticketing.pricing.domain.model.PriceHistory;
import com.ticketing.pricing.domain.repository.EventPriceRuleRepository;
import com.ticketing.pricing.domain.repository.PriceHistoryRepository;
import com.ticketing.pricing.dto.request.CreatePriceRuleRequest;
import com.ticketing.pricing.dto.request.UpdatePriceRuleRequest;
import com.ticketing.pricing.dto.response.EffectivePriceResponse;
import com.ticketing.pricing.dto.response.PriceRuleResponse;
import com.ticketing.pricing.kafka.PricingEventPublisher;
import com.ticketing.pricing.mapper.PriceRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    /** How far back we search price_history to decide if a price is "stale vs fabricated". */
    private static final Duration HISTORY_VALIDITY_WINDOW   = Duration.ofMinutes(30);
    /** How long a user has to confirm a price change before the saga times out. */
    private static final Duration CONFIRM_WINDOW            = Duration.ofMinutes(5);

    private final EventPriceRuleRepository      repository;
    private final PriceHistoryRepository        priceHistoryRepository;
    private final PriceRuleMapper               mapper;
    private final PricingEventPublisher         publisher;
    private final TicketValidationClient        ticketValidationClient;

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    @Transactional
    @CachePut(value = CacheConfig.PRICE_RULES_CACHE, key = "#result.eventId")
    public PriceRuleResponse createRule(CreatePriceRuleRequest request) {
        // Validate event exists in ticket-service (fail-closed)
        ticketValidationClient.validateEventExists(request.getEventId());

        if (repository.findByEventId(request.getEventId()).isPresent()) {
            throw new IllegalArgumentException("Price rule already exists for event: " + request.getEventId());
        }
        EventPriceRule rule = mapper.toEntity(request);
        rule = repository.save(rule);
        writeHistory(rule.getEventId(), rule.getSurgeMultiplier(), "MANUAL");
        log.info("Created price rule for eventId={} surgeMultiplier={}", rule.getEventId(), rule.getSurgeMultiplier());
        return mapper.toResponse(rule);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.PRICE_RULES_CACHE, key = "#eventId")
    public PriceRuleResponse getRule(String eventId) {
        return repository.findByEventId(eventId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("No price rule found for event: " + eventId));
    }

    @Transactional
    @CachePut(value = CacheConfig.PRICE_RULES_CACHE, key = "#eventId")
    public PriceRuleResponse updateRule(String eventId, UpdatePriceRuleRequest request) {
        EventPriceRule rule = repository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalArgumentException("No price rule found for event: " + eventId));
        mapper.updateEntity(request, rule);
        rule = repository.save(rule);
        writeHistory(rule.getEventId(), rule.getSurgeMultiplier(), "MANUAL");
        log.info("Updated price rule for eventId={} surgeMultiplier={}", eventId, rule.getSurgeMultiplier());
        return mapper.toResponse(rule);
    }

    // ── Lock / Unlock ─────────────────────────────────────────────────────────

    @Transactional
    public void lockPrice(PriceLockCommand cmd) {
        log.info("lockPrice: sagaId={} orderId={} ticketId={} userPrice={} facePrice={} confirmed={}",
                cmd.getSagaId(), cmd.getOrderId(), cmd.getTicketId(),
                cmd.getUserPrice(), cmd.getFacePrice(), cmd.isConfirmed());

        EventPriceRule rule = repository.findByEventId(cmd.getEventId()).orElse(null);

        if (rule == null) {
            log.warn("No price rule for eventId={}, failing saga sagaId={}", cmd.getEventId(), cmd.getSagaId());
            publisher.publishPricingFailed(new PricingFailedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getOrderId(), cmd.getTicketId(), ErrorCode.NO_PRICE_RULE.name()));
            return;
        }

        BigDecimal facePrice = cmd.getFacePrice();

        // ── Confirmed re-lock: user already agreed to the new price ───────────
        if (cmd.isConfirmed()) {
            BigDecimal expectedPrice = facePrice.multiply(rule.getSurgeMultiplier())
                    .setScale(2, RoundingMode.HALF_UP);
            publisher.publishPricingLocked(new PricingLockedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), expectedPrice));
            log.info("Confirmed price lock: sagaId={} price={}", cmd.getSagaId(), expectedPrice);
            return;
        }

        // ── Normal lock: validate userPrice against multiplier history ────────
        BigDecimal multiplierAtOrderTime = priceHistoryRepository
                .findMultiplierAt(cmd.getEventId(), cmd.getOrderCreatedAt());

        if (multiplierAtOrderTime == null) {
            // No history — failsafe: use current multiplier
            log.warn("No multiplier history at orderCreatedAt={} for eventId={}, using current",
                    cmd.getOrderCreatedAt(), cmd.getEventId());
            BigDecimal expectedPrice = facePrice.multiply(rule.getSurgeMultiplier())
                    .setScale(2, RoundingMode.HALF_UP);
            publisher.publishPricingLocked(new PricingLockedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), expectedPrice));
            return;
        }

        // Compute what the user's claimed multiplier was: userPrice / facePrice
        BigDecimal claimedMultiplier = cmd.getUserPrice()
                .divide(facePrice, 4, RoundingMode.HALF_UP);

        boolean multiplierEverExisted = priceHistoryRepository.existsInRecentHistory(
                cmd.getEventId(), claimedMultiplier,
                Instant.now().minus(HISTORY_VALIDITY_WINDOW));

        // Case A — claimed multiplier never existed: fabricated price
        if (!multiplierEverExisted) {
            log.warn("Fabricated price {} (claimedMultiplier={}) for eventId={} sagaId={}",
                    cmd.getUserPrice(), claimedMultiplier, cmd.getEventId(), cmd.getSagaId());
            publisher.publishPricingFailed(new PricingFailedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getOrderId(), cmd.getTicketId(), ErrorCode.INVALID_PRICE.name()));
            return;
        }

        BigDecimal expectedPrice = facePrice.multiply(multiplierAtOrderTime)
                .setScale(2, RoundingMode.HALF_UP);

        // Case B — userPrice matches expected price: proceed
        if (cmd.getUserPrice().compareTo(expectedPrice) == 0) {
            publisher.publishPricingLocked(new PricingLockedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), expectedPrice));
            log.info("Normal price lock: sagaId={} price={}", cmd.getSagaId(), expectedPrice);
            return;
        }

        // Case C — multiplier was real but has since changed: ask user to confirm
        BigDecimal newExpectedPrice = facePrice.multiply(rule.getSurgeMultiplier())
                .setScale(2, RoundingMode.HALF_UP);
        log.info("Price changed for sagaId={}: userPrice={} newExpectedPrice={}",
                cmd.getSagaId(), cmd.getUserPrice(), newExpectedPrice);
        publisher.publishPriceChanged(new PriceChangedEvent(
                cmd.getTraceId(), cmd.getSagaId(),
                cmd.getOrderId(), cmd.getTicketId(),
                cmd.getUserPrice(), newExpectedPrice,
                Instant.now().plus(CONFIRM_WINDOW)));
    }

    // ── Dynamic pricing ───────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void recalculatePrices() {
        log.debug("Starting price recalculation cycle");
        List<EventPriceRule> rules = repository.findAll();

        for (EventPriceRule rule : rules) {
            double newDemandFactor = computeDemandFactor(rule);
            BigDecimal newMultiplier = calculateSurgeMultiplier(rule, newDemandFactor);

            if (newMultiplier.compareTo(rule.getSurgeMultiplier()) != 0) {
                rule.setSurgeMultiplier(newMultiplier);
                rule.setDemandFactor(newDemandFactor);
                repository.save(rule);

                writeHistory(rule.getEventId(), newMultiplier, "DEMAND");

                publisher.publishPriceUpdated(new PriceUpdatedEvent(
                        UUID.randomUUID().toString(), null,
                        rule.getEventId(), null, newMultiplier,
                        null, null));

                log.debug("Surge recalculated: eventId={} newMultiplier={}", rule.getEventId(), newMultiplier);
            }
        }
    }

    // ── Effective price ───────────────────────────────────────────────────────

    /**
     * Returns the effective price for a specific ticket.
     * facePrice is fetched from ticket-service and cached in Caffeine (10 min TTL).
     * effectivePrice = facePrice * currentSurgeMultiplier
     */
    @Transactional(readOnly = true)
    public EffectivePriceResponse getEffectivePrice(String ticketId) {
        // Single cached call — returns both facePrice and eventId
        var ticket = ticketValidationClient.getTicketSummary(ticketId);

        EventPriceRule rule = repository.findByEventId(ticket.getEventId())
                .orElseThrow(() -> new IllegalArgumentException("No price rule for event: " + ticket.getEventId()));

        BigDecimal effectivePrice = ticket.getFacePrice().multiply(rule.getSurgeMultiplier())
                .setScale(2, RoundingMode.HALF_UP);

        return EffectivePriceResponse.builder()
                .ticketId(ticketId)
                .eventId(ticket.getEventId())
                .facePrice(ticket.getFacePrice())
                .surgeMultiplier(rule.getSurgeMultiplier())
                .effectivePrice(effectivePrice)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Closes the current surge_multiplier history record and opens a new one.
     */
    private void writeHistory(String eventId, BigDecimal surgeMultiplier, String triggeredBy) {
        Instant now = Instant.now();
        priceHistoryRepository.closeActive(eventId, now);
        priceHistoryRepository.save(PriceHistory.builder()
                .eventId(eventId)
                .surgeMultiplier(surgeMultiplier)
                .validFrom(now)
                .validTo(null)
                .triggeredBy(triggeredBy)
                .build());
    }

    private BigDecimal calculateSurgeMultiplier(EventPriceRule rule, double demandFactor) {
        double timeFactor  = computeTimeFactor(rule);
        double maxSurge    = rule.getMaxSurge().doubleValue();
        double multiplier  = 1.0 + (maxSurge - 1.0) * demandFactor * timeFactor;
        multiplier = Math.max(1.0, Math.min(multiplier, maxSurge));
        return BigDecimal.valueOf(multiplier).setScale(4, RoundingMode.HALF_UP);
    }

    private double computeDemandFactor(EventPriceRule rule) {
        if (rule.getTotalTickets() <= 0) return 0.0;
        return (double) rule.getSoldTickets() / rule.getTotalTickets();
    }

    private double computeTimeFactor(EventPriceRule rule) {
        if (rule.getEventDate() == null) return 1.0;
        long hoursToEvent = Duration.between(Instant.now(), rule.getEventDate()).toHours();
        return hoursToEvent < 24 ? 1.10 : 1.0;
    }

}
