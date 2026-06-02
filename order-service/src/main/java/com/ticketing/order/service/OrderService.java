package com.ticketing.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.*;
import com.ticketing.order.client.EventValidationClient;
import com.ticketing.order.client.ReservationAccessClient;
import com.ticketing.order.domain.model.Order;
import com.ticketing.order.domain.model.OrderStatus;
import com.ticketing.order.domain.repository.OrderRepository;
import com.ticketing.order.dto.request.CreateOrderRequest;
import com.ticketing.order.dto.response.OrderResponse;
import com.ticketing.order.kafka.OrderEventPublisher;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.sse.OrderSseRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class OrderService {

    private static final String   REDIS_ORDER_KEY_PREFIX = "order:";
    private static final Duration REDIS_TTL              = Duration.ofMinutes(5);

    /** Cross-pod fast-fail intent lock — held for the few-ms window between
     *  POST acceptance and the saga's authoritative SETNX on ticket:lock. */
    private static final String   INTENT_LOCK_PREFIX = "order-intent:";
    private static final Duration INTENT_LOCK_TTL    = Duration.ofSeconds(5);

    private final OrderRepository         orderRepository;
    private final OrderMapper             orderMapper;
    private final OrderEventPublisher     eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper            objectMapper;
    private final EventValidationClient   eventValidationClient;
    private final ReservationAccessClient reservationAccessClient;
    private final OrderSseRegistry        sseRegistry;
    private final Executor                guardCheckExecutor;
    private final TicketStatusCache       ticketStatusCache;

    public OrderService(OrderRepository orderRepository,
                        OrderMapper orderMapper,
                        OrderEventPublisher eventPublisher,
                        RedisTemplate<String, String> redisTemplate,
                        ObjectMapper objectMapper,
                        EventValidationClient eventValidationClient,
                        ReservationAccessClient reservationAccessClient,
                        OrderSseRegistry sseRegistry,
                        @Qualifier("guardCheckExecutor") Executor guardCheckExecutor,
                        TicketStatusCache ticketStatusCache) {
        this.orderRepository         = orderRepository;
        this.orderMapper             = orderMapper;
        this.eventPublisher          = eventPublisher;
        this.redisTemplate           = redisTemplate;
        this.objectMapper            = objectMapper;
        this.eventValidationClient   = eventValidationClient;
        this.reservationAccessClient = reservationAccessClient;
        this.sseRegistry             = sseRegistry;
        this.guardCheckExecutor      = guardCheckExecutor;
        this.ticketStatusCache       = ticketStatusCache;
    }

    @Transactional
    public OrderResponse createOrder(String userId, String traceId, CreateOrderRequest request) {
        String ticketId = request.getTicketId();

        // ── Fast-fail Tier 1 — per-pod Caffeine cache (µs) ────────────────────
        // Populated by TicketStateConsumer subscribing to ticket.reserved /
        // confirmed / released. Catches the dominant race case: many users
        // clicking Buy on the same hot ticket within ms of each other. If a
        // recent ticket.reserved event already propagated to this pod, we
        // reject immediately without touching DB, Redis, or Kafka.
        if (ticketStatusCache.isTaken(ticketId)) {
            throw new IllegalStateException(
                    "Ticket " + ticketId + " is already reserved or sold — try a different ticket.");
        }

        // ── Fast-fail Tier 2 — cross-pod Redis intent-lock (~0.5 ms) ──────────
        // SETNX with a 5 s TTL. Two simultaneous winners of Tier 1 (because
        // their local caches missed independently) race here on Redis — the
        // loser gets a fast 409 without creating Order / publishing Kafka.
        // The 5 s TTL covers the ~ms window until the saga's own SETNX on
        // ticket:lock takes authoritative ownership; TicketStateConsumer
        // proactively deletes this key on ticket.reserved to free it sooner.
        String intentKey = INTENT_LOCK_PREFIX + ticketId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(intentKey, UUID.randomUUID().toString(), INTENT_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException(
                    "Ticket " + ticketId + " has a pending checkout — try again in a moment.");
        }

        try {
            // Guards 1 & 2 — run in parallel (saves ~15 ms vs serial execution).
            // Both clients are fail-open on exception, so the futures never complete exceptionally.
            CompletableFuture<Boolean> eventOpenFuture = CompletableFuture.supplyAsync(
                    () -> eventValidationClient.isEventOpenForSales(ticketId),
                    guardCheckExecutor);
            CompletableFuture<Boolean> queueAccessFuture = CompletableFuture.supplyAsync(
                    () -> reservationAccessClient.isAllowedToPurchase(ticketId, userId),
                    guardCheckExecutor);

            // Block until both finish — total wait = max(guard1, guard2), not sum.
            boolean eventOpen    = eventOpenFuture.join();
            boolean queueAllowed = queueAccessFuture.join();

            if (!eventOpen) {
                // Release intent lock so a corrected request from another user isn't blocked.
                redisTemplate.delete(intentKey);
                throw new IllegalStateException(
                        "Event is not open for sales for ticket: " + ticketId);
            }
            if (!queueAllowed) {
                redisTemplate.delete(intentKey);
                throw new IllegalStateException(
                        "A different user currently holds the exclusive purchase window for ticket: "
                        + ticketId
                        + ". Please join the queue via POST /api/reservations and wait for your turn.");
            }

            return createOrderInner(userId, traceId, request);
        } catch (RuntimeException e) {
            // Anything else (DB error, publisher failure) — release the lock so
            // a retry isn't gated for 5 s on stale state.
            redisTemplate.delete(intentKey);
            throw e;
        }
        // Note: on the SUCCESS path the intent-lock is NOT deleted here. It
        // will be released by TicketStateConsumer when ticket.reserved arrives
        // (typically ms after this method returns), or by its 5 s TTL as a
        // last-resort safety net.
    }

    /** Original Order creation body, extracted so the fast-fail wrapper above can stay readable. */
    private OrderResponse createOrderInner(String userId, String traceId, CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        String sagaId  = UUID.randomUUID().toString();

        // Capture wall-clock time BEFORE save so it matches the price-history window the
        // user was seeing. @CreationTimestamp is populated by Hibernate during flush (after
        // the transaction commits), not synchronously inside save() — reading order.getCreatedAt()
        // here would return null and break the point-in-time price validation in pricing-service.
        Instant orderCreatedAt = Instant.now();

        var order = Order.builder()
                .id(orderId)
                .userId(userId)
                .ticketId(request.getTicketId())
                .sagaId(sagaId)
                .status(OrderStatus.PENDING)
                .requestedPrice(request.getRequestedPrice())
                .build();

        orderRepository.save(order);
        log.info("Created order orderId={} userId={} ticketId={} sagaId={} traceId={}",
                orderId, userId, request.getTicketId(), sagaId, traceId);

        // Pass orderCreatedAt (wall-clock stamped) and userPrice for point-in-time price validation
        eventPublisher.publishOrderCreated(
                traceId, sagaId, orderId, userId,
                request.getTicketId(), request.getRequestedPrice(),
                orderCreatedAt);

        return orderMapper.toResponse(order);
    }

    // sync=true coalesces concurrent misses on the same orderId — a flash-sale
    // refresh-storm on the tracker page can otherwise produce N identical DB
    // queries; per-JVM Spring Cache locks them to one loader.
    @Cacheable(value = "orders", key = "#id", sync = true)
    @Transactional(readOnly = true)
    public OrderResponse getOrder(String id) {
        String redisKey = REDIS_ORDER_KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            try { return objectMapper.readValue(cached, OrderResponse.class); }
            catch (JsonProcessingException e) { log.warn("Redis deser failed key={}", redisKey); }
        }
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + id));
        OrderResponse response = orderMapper.toResponse(order);
        writeL2(redisKey, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByUser(String userId) {
        return orderMapper.toResponseList(orderRepository.findByUserId(userId));
    }

    // ── Saga outcome handlers ─────────────────────────────────────────────────

    @CacheEvict(value = "orders", key = "#event.orderId")
    @Transactional
    public void handleConfirmed(OrderConfirmedEvent event) {
        Order order = findOrThrow(event.getOrderId());
        order.setStatus(OrderStatus.CONFIRMED);
        order.setFinalPrice(event.getFinalPrice());
        order.setPaymentReference(event.getPaymentReference());
        orderRepository.save(order);
        evictL2(event.getOrderId());
        log.info("Order confirmed orderId={}", event.getOrderId());

        // Terminal event — push and close the stream
        sseRegistry.complete(event.getOrderId(), "confirmed", Map.of(
                "orderId",           event.getOrderId(),
                "finalPrice",        event.getFinalPrice(),
                "paymentReference",  event.getPaymentReference()));
    }

    @CacheEvict(value = "orders", key = "#event.orderId")
    @Transactional
    public void handleFailed(OrderFailedEvent event) {
        Order order = findOrThrow(event.getOrderId());
        order.setStatus(OrderStatus.FAILED);
        order.setFailureReason(event.getReason());
        orderRepository.save(order);
        evictL2(event.getOrderId());
        log.info("Order failed orderId={} reason={}", event.getOrderId(), event.getReason());

        // Terminal event — push and close the stream
        sseRegistry.complete(event.getOrderId(), "failed", Map.of(
                "orderId", event.getOrderId(),
                "reason",  event.getReason()));
    }

    @CacheEvict(value = "orders", key = "#event.orderId")
    @Transactional
    public void handlePriceChanged(OrderPriceChangedEvent event) {
        Order order = findOrThrow(event.getOrderId());
        order.setStatus(OrderStatus.PRICE_CHANGED);
        order.setPendingPrice(event.getNewPrice());
        orderRepository.save(order);
        evictL2(event.getOrderId());
        log.info("Order price changed orderId={} oldPrice={} newPrice={}",
                event.getOrderId(), event.getOldPrice(), event.getNewPrice());

        // Non-terminal — push but keep the stream open for confirm/cancel
        sseRegistry.push(event.getOrderId(), "price-changed", Map.of(
                "orderId",           event.getOrderId(),
                "oldPrice",          event.getOldPrice(),
                "newPrice",          event.getNewPrice(),
                "confirmExpiresAt",  event.getConfirmExpiresAt()));
    }

    @CacheEvict(value = "orders", key = "#event.orderId")
    @Transactional
    public void handleCancelled(OrderCancelledEvent event) {
        Order order = findOrThrow(event.getOrderId());
        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason(event.getReason());
        orderRepository.save(order);
        evictL2(event.getOrderId());
        log.info("Order cancelled orderId={} reason={}", event.getOrderId(), event.getReason());

        // Terminal event — push and close the stream
        sseRegistry.complete(event.getOrderId(), "cancelled", Map.of(
                "orderId", event.getOrderId(),
                "reason",  event.getReason()));
    }

    // ── User-initiated price confirm/cancel ───────────────────────────────────

    @Transactional
    public void confirmPrice(String orderId, String userId, String traceId) {
        Order order = findOrThrow(orderId);
        validateOwner(order, userId);
        if (order.getStatus() != OrderStatus.PRICE_CHANGED) {
            throw new IllegalStateException(
                    "Order is not awaiting price confirmation: " + order.getStatus());
        }
        // Put order back to PENDING — will be updated again when saga resumes
        order.setStatus(OrderStatus.PENDING);
        orderRepository.save(order);
        evictL2(orderId);
        eventPublisher.publishPriceConfirm(traceId, order.getSagaId(), orderId, userId);
        log.info("User confirmed price for orderId={}", orderId);
    }

    @Transactional
    public void cancelPrice(String orderId, String userId, String traceId) {
        Order order = findOrThrow(orderId);
        validateOwner(order, userId);
        if (order.getStatus() != OrderStatus.PRICE_CHANGED) {
            throw new IllegalStateException(
                    "Order is not awaiting price confirmation: " + order.getStatus());
        }
        // Saga will send the actual CANCELLED event back; we optimistically set CANCELLED
        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason("User rejected price change");
        orderRepository.save(order);
        evictL2(orderId);
        eventPublisher.publishPriceCancel(traceId, order.getSagaId(), orderId, userId);
        log.info("User cancelled price for orderId={}", orderId);
    }

    // ── SSE ownership guard ───────────────────────────────────────────────────

    /** Called by the SSE endpoint to confirm the requesting user owns this order. */
    @Transactional(readOnly = true)
    public void verifyOwner(String orderId, String userId) {
        validateOwner(findOrThrow(orderId), userId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Order findOrThrow(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
    }

    private void validateOwner(Order order, String userId) {
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Order does not belong to user: " + userId);
        }
    }

    private void writeL2(String key, OrderResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), REDIS_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to write Redis cache key={}", key);
        }
    }

    private void evictL2(String orderId) {
        try { redisTemplate.delete(REDIS_ORDER_KEY_PREFIX + orderId); }
        catch (Exception e) { log.warn("Failed to evict Redis cache orderId={}", orderId); }
    }
}
