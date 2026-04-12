package com.ticketing.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.OrderConfirmedEvent;
import com.ticketing.common.events.OrderFailedEvent;
import com.ticketing.order.client.EventValidationClient;
import com.ticketing.order.domain.model.Order;
import com.ticketing.order.domain.model.OrderStatus;
import com.ticketing.order.domain.repository.OrderRepository;
import com.ticketing.order.dto.request.CreateOrderRequest;
import com.ticketing.order.dto.response.OrderResponse;
import com.ticketing.order.kafka.OrderEventPublisher;
import com.ticketing.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String REDIS_ORDER_KEY_PREFIX = "order:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    private final OrderRepository       orderRepository;
    private final OrderMapper           orderMapper;
    private final OrderEventPublisher   eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper          objectMapper;
    private final EventValidationClient eventValidationClient;

    @Transactional
    public OrderResponse createOrder(String userId, String traceId, CreateOrderRequest request) {
        // Early guard: reject if ticket-service explicitly reports event closed.
        // Fails open when ticket-service is unreachable (saga guard is authoritative).
        if (!eventValidationClient.isEventOpenForSales(request.getTicketId())) {
            throw new IllegalStateException(
                    "Event is not open for sales for ticket: " + request.getTicketId());
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

        eventPublisher.publishOrderCreated(
                traceId, sagaId, orderId, userId,
                request.getTicketId(), request.getRequestedPrice());

        return orderMapper.toResponse(order);
    }

    /**
     * Read with L1 (Caffeine) → L2 (Redis) → DB fallback.
     * @Cacheable handles L1; Redis is checked manually before DB.
     */
    @Cacheable(value = "orders", key = "#id")
    @Transactional(readOnly = true)
    public OrderResponse getOrder(String id) {
        // L2 Redis check
        String redisKey = REDIS_ORDER_KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, OrderResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize order from Redis key={}: {}", redisKey, e.getMessage());
            }
        }

        // DB (routed to slave via readOnly transaction)
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + id));

        OrderResponse response = orderMapper.toResponse(order);

        // Populate L2
        try {
            redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(response), REDIS_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize order to Redis key={}: {}", redisKey, e.getMessage());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByUser(String userId) {
        return orderMapper.toResponseList(orderRepository.findByUserId(userId));
    }

    @CacheEvict(value = "orders", key = "#event.orderId")
    @Transactional
    public void handleConfirmed(OrderConfirmedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + event.getOrderId()));

        order.setStatus(OrderStatus.CONFIRMED);
        order.setFinalPrice(event.getFinalPrice());
        order.setPaymentReference(event.getPaymentReference());
        orderRepository.save(order);

        evictRedisCache(event.getOrderId());

        log.info("Order confirmed orderId={} paymentReference={}", event.getOrderId(), event.getPaymentReference());
    }

    @CacheEvict(value = "orders", key = "#event.orderId")
    @Transactional
    public void handleFailed(OrderFailedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + event.getOrderId()));

        order.setStatus(OrderStatus.FAILED);
        order.setFailureReason(event.getReason());
        orderRepository.save(order);

        evictRedisCache(event.getOrderId());

        log.info("Order failed orderId={} reason={}", event.getOrderId(), event.getReason());
    }

    private void evictRedisCache(String orderId) {
        try {
            redisTemplate.delete(REDIS_ORDER_KEY_PREFIX + orderId);
        } catch (Exception e) {
            log.warn("Failed to evict Redis cache for orderId={}: {}", orderId, e.getMessage());
        }
    }
}
