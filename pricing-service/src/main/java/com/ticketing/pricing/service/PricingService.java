package com.ticketing.pricing.service;

import com.ticketing.common.events.*;
import com.ticketing.pricing.config.CacheConfig;
import com.ticketing.pricing.domain.model.EventPriceRule;
import com.ticketing.pricing.domain.repository.EventPriceRuleRepository;
import com.ticketing.pricing.dto.request.CreatePriceRuleRequest;
import com.ticketing.pricing.dto.request.UpdatePriceRuleRequest;
import com.ticketing.pricing.dto.response.PriceRuleResponse;
import com.ticketing.pricing.kafka.PricingEventPublisher;
import com.ticketing.pricing.mapper.PriceRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private static final String PRICE_LOCK_KEY_PREFIX = "price:lock:";
    private static final long   LOCK_TTL_MINUTES      = 10;

    private final EventPriceRuleRepository      repository;
    private final PriceRuleMapper               mapper;
    private final PricingEventPublisher         publisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ConcurrentHashMap<String, List<SseEmitter>> sseEmitterRegistry;

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    @Transactional
    @CachePut(value = CacheConfig.PRICE_RULES_CACHE, key = "#result.eventId")
    public PriceRuleResponse createRule(CreatePriceRuleRequest request) {
        if (repository.findByEventId(request.getEventId()).isPresent()) {
            throw new IllegalArgumentException("Price rule already exists for event: " + request.getEventId());
        }
        EventPriceRule rule = mapper.toEntity(request);
        rule = repository.save(rule);
        log.info("Created price rule for eventId={}", rule.getEventId());
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
        log.info("Updated price rule for eventId={}", eventId);
        return mapper.toResponse(rule);
    }

    // ── Lock / Unlock ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void lockPrice(PriceLockCommand cmd) {
        log.info("Locking price: sagaId={}, ticketId={}, orderId={}, requestedPrice={}",
                cmd.getSagaId(), cmd.getTicketId(), cmd.getOrderId(), cmd.getRequestedPrice());

        EventPriceRule rule = repository.findByEventId(cmd.getEventId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No price rule found for event: " + cmd.getEventId()));

        BigDecimal requested = cmd.getRequestedPrice();
        if (requested.compareTo(rule.getMinPrice()) < 0 || requested.compareTo(rule.getMaxPrice()) > 0) {
            throw new IllegalArgumentException(
                    String.format("Requested price %s is outside allowed range [%s, %s]",
                            requested, rule.getMinPrice(), rule.getMaxPrice()));
        }

        String key = lockKey(cmd.getTicketId(), cmd.getOrderId());
        redisTemplate.opsForValue().set(key, requested.toPlainString(),
                Duration.ofMinutes(LOCK_TTL_MINUTES));

        publisher.publishPricingLocked(
                new PricingLockedEvent(cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(), requested));

        log.info("Price locked: key={}, price={}", key, requested);
    }

    public void unlockPrice(PriceUnlockCommand cmd) {
        String key = lockKey(cmd.getTicketId(), cmd.getOrderId());
        Boolean deleted = redisTemplate.delete(key);
        log.info("Price unlocked: key={}, deleted={}, reason={}", key, deleted, cmd.getReason());
    }

    // ── Dynamic pricing ───────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void recalculatePrices() {
        log.debug("Starting price recalculation cycle");
        List<EventPriceRule> rules = repository.findAll();

        for (EventPriceRule rule : rules) {
            BigDecimal newPrice = calculateDynamicPrice(rule);

            if (newPrice.compareTo(rule.getCurrentPrice()) != 0) {
                rule.setCurrentPrice(newPrice);
                rule.setDemandFactor(computeDemandFactor(rule));
                repository.save(rule);

                PriceRuleResponse response = mapper.toResponse(rule);

                // Publish to Kafka (for WebSocket push etc.)
                publisher.publishPriceUpdated(
                        new PriceUpdatedEvent(
                                UUID.randomUUID().toString(),
                                null,
                                rule.getEventId(),
                                null,
                                newPrice,
                                rule.getMinPrice(),
                                rule.getMaxPrice()));

                // Push SSE update
                pushSseUpdate(rule.getEventId(), response);

                log.debug("Price recalculated: eventId={}, newPrice={}", rule.getEventId(), newPrice);
            }
        }
    }

    /**
     * Dynamic pricing formula:
     *   - Base: currentPrice
     *   - Demand factor: soldRatio (0..1) → higher sold % increases price
     *   - Time factor: < 24h to event → +10% surcharge
     *   - Result is clamped to [minPrice, maxPrice]
     */
    private BigDecimal calculateDynamicPrice(EventPriceRule rule) {
        double demandFactor = computeDemandFactor(rule);
        double timeFactor   = computeTimeFactor(rule);

        BigDecimal base = rule.getMinPrice()
                .add(rule.getMaxPrice().subtract(rule.getMinPrice())
                        .multiply(BigDecimal.valueOf(demandFactor * timeFactor)));

        // Clamp
        if (base.compareTo(rule.getMinPrice()) < 0) base = rule.getMinPrice();
        if (base.compareTo(rule.getMaxPrice()) > 0) base = rule.getMaxPrice();

        return base.setScale(2, RoundingMode.HALF_UP);
    }

    private double computeDemandFactor(EventPriceRule rule) {
        if (rule.getTotalTickets() <= 0) return 0.5;
        return (double) rule.getSoldTickets() / rule.getTotalTickets();
    }

    private double computeTimeFactor(EventPriceRule rule) {
        if (rule.getEventDate() == null) return 1.0;
        long hoursToEvent = Duration.between(Instant.now(), rule.getEventDate()).toHours();
        return hoursToEvent < 24 ? 1.10 : 1.0;
    }

    // ── SSE ───────────────────────────────────────────────────────────────────

    public void pushSseUpdate(String eventId, PriceRuleResponse price) {
        List<SseEmitter> emitters = sseEmitterRegistry.getOrDefault(eventId, List.of());
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("price-update")
                        .data(price));
            } catch (Exception e) {
                log.warn("SSE send failed for eventId={}, removing emitter", eventId);
                dead.add(emitter);
            }
        }

        if (!dead.isEmpty()) {
            sseEmitterRegistry.computeIfPresent(eventId, (k, list) -> {
                list.removeAll(dead);
                return list.isEmpty() ? null : list;
            });
        }
    }

    public SseEmitter registerSseEmitter(String eventId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        sseEmitterRegistry.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> sseEmitterRegistry.computeIfPresent(eventId, (k, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Send current price immediately on connect
        try {
            PriceRuleResponse current = getRule(eventId);
            emitter.send(SseEmitter.event().name("price-init").data(current));
        } catch (Exception e) {
            log.warn("Could not send initial price for eventId={}: {}", eventId, e.getMessage());
        }

        return emitter;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String lockKey(String ticketId, String orderId) {
        return PRICE_LOCK_KEY_PREFIX + ticketId + ":" + orderId;
    }
}
