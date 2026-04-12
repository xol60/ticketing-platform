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

    /**
     * Create a new order.
     * User identity comes from the trusted gateway header X-User-Id.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> createOrder(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @Valid @RequestBody CreateOrderRequest request) {

        log.debug("POST /api/orders userId={} traceId={} ticketId={}", userId, traceId, request.getTicketId());
        OrderResponse response = orderService.createOrder(userId, traceId, request);
        return ApiResponse.ok(response, traceId);
    }

    /**
     * Get a single order by id.
     */
    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> getOrder(
            @PathVariable String id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        OrderResponse response = orderService.getOrder(id);
        return ApiResponse.ok(response, traceId);
    }

    /**
     * List orders by userId query param.
     */
    @GetMapping
    public ApiResponse<List<OrderResponse>> getOrdersByUser(
            @RequestParam String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        List<OrderResponse> orders = orderService.getOrdersByUser(userId);
        return ApiResponse.ok(orders, traceId);
    }
}
