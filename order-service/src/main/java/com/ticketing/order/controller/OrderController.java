package com.ticketing.order.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.order.dto.request.CreateOrderRequest;
import com.ticketing.order.dto.response.OrderResponse;
import com.ticketing.order.service.OrderService;
import com.ticketing.order.sse.OrderSseRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService    orderService;
    private final OrderSseRegistry sseRegistry;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> createOrder(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.createOrder(userId, traceId, request), traceId);
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> getOrder(
            @PathVariable String id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(orderService.getOrder(id), traceId);
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> getOrdersByUser(
            @RequestParam String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(orderService.getOrdersByUser(userId), traceId);
    }

    /**
     * User confirms the new price shown during PRICE_CHANGED state.
     * Saga resumes with confirmed=true price lock.
     */
    @PostMapping("/{id}/confirm-price")
    public ApiResponse<Void> confirmPrice(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        orderService.confirmPrice(id, userId, traceId);
        return ApiResponse.ok(null, traceId);
    }

    /**
     * User rejects the new price. Saga compensates, ticket is released.
     */
    @PostMapping("/{id}/cancel-price")
    public ApiResponse<Void> cancelPrice(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        orderService.cancelPrice(id, userId, traceId);
        return ApiResponse.ok(null, traceId);
    }

    /**
     * SSE stream for real-time order events.
     *
     * Client opens this endpoint immediately after POST /api/orders and holds
     * the connection open. The server pushes named events:
     *   - "price-changed"  → user must confirm/cancel via POST .../confirm-price or .../cancel-price
     *   - "confirmed"      → saga completed successfully  (stream closes)
     *   - "failed"         → saga failed                  (stream closes)
     *   - "cancelled"      → saga or user cancelled       (stream closes)
     *
     * Stream auto-closes after 7 minutes (slightly longer than the 6-minute
     * price-confirm window) if the saga has not yet reached a terminal state.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOrderEvents(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {
        orderService.verifyOwner(id, userId);
        log.info("SSE stream opened orderId={} userId={}", id, userId);
        return sseRegistry.register(id);
    }
}
