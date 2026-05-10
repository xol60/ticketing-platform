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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String   REDIS_ORDER_KEY_PREFIX = "order:";
    private static final Duration REDIS_TTL              = Duration.ofMinutes(5);

    private final OrderRepository       orderRepository;
    private final OrderMapper           orderMapper;
    private final OrderEventPublisher   eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper          objectMapper;
    private final EventValidationClient   eventValidationClient;
    private final ReservationAccessClient reservationAccessClient;
    private final OrderSseRegistry        sseRegistry;

    @Transactional
    public OrderResponse createOrder(String userId, String traceId, CreateOrderRequest request) {
        // Guard 1: reject if ticket-service explicitly says event is closed
        if (!eventValidationClient.isEventOpenForSales(request.getTicketId())) {
            throw new IllegalStateException(
                    "Event is not open for sales for ticket: " + request.getTicketId());
        }

        // Guard 2: queue fairness — reject if another user holds the exclusive purchase window.
        // Fail-open: if reservation-service is down we allow the order (ticket-service
        // pessimistic lock is the final overselling guard; queue is a fairness mechanism).
        if (!reservationAccessClient.isAllowedToPurchase(request.getTicketId(), userId)) {
            throw new IllegalStateException(
                    "A different user currently holds the exclusive purchase window for ticket: "
                    + request.getTicketId()
                    + ". Please join the queue via POST /api/reservations and wait for your turn.");
        }

        String orderId = UUID.randomUUID().toString();
        String sagaId  = UUID.randomUUID().toString();

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

        // Pass orderCreatedAt (DB-stamped) and userPrice for point-in-time price validation
        eventPublisher.publishOrderCreated(
                traceId, sagaId, orderId, userId,
                request.getTicketId(), request.getRequestedPrice(),
                order.getCreatedAt());

        return orderMapper.toResponse(order);
    }

    @Cacheable(value = "orders", key = "#id")
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
