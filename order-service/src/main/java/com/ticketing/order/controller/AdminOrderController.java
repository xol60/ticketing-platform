package com.ticketing.order.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.order.domain.model.Order;
import com.ticketing.order.domain.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderRepository orderRepository;

    @GetMapping
    public ApiResponse<Page<Order>> listOrders(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<Order> result = orderRepository
                .findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ApiResponse.ok(result, traceId);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<Order> getOrder(
            @PathVariable String orderId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
        return ApiResponse.ok(order, traceId);
    }
}
