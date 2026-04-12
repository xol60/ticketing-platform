package com.ticketing.payment.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.payment.dto.response.PaymentResponse;
import com.ticketing.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * GET /api/payments/{orderId}
     * Returns full payment record for the given order.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@PathVariable String orderId) {
        log.debug("[PaymentController] GET /api/payments/{}", orderId);
        PaymentResponse response = paymentService.getPaymentByOrderId(orderId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * GET /api/payments/{orderId}/status
     * Returns only the payment status string.
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<ApiResponse<String>> getPaymentStatus(@PathVariable String orderId) {
        log.debug("[PaymentController] GET /api/payments/{}/status", orderId);
        PaymentResponse response = paymentService.getPaymentByOrderId(orderId);
        return ResponseEntity.ok(ApiResponse.ok(response.getStatus().name()));
    }
}
