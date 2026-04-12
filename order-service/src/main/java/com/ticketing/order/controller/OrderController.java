package com.ticketing.order.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.order.dto.request.CreateOrderRequest;
import com.ticketing.order.dto.response.OrderResponse;
import com.ticketing.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

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
}
