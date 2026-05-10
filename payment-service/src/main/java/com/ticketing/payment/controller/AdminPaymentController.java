package com.ticketing.payment.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.payment.domain.model.Payment;
import com.ticketing.payment.domain.model.PaymentStatus;
import com.ticketing.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentRepository paymentRepository;

    @GetMapping
    public ApiResponse<Page<Payment>> listPayments(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<Payment> result = paymentRepository
                .findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ApiResponse.ok(result, traceId);
    }

    @GetMapping("/failed")
    public ApiResponse<Page<Payment>> listFailed(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<Payment> result = paymentRepository
                .findByStatus(PaymentStatus.FAILED,
                              PageRequest.of(page, size, Sort.by("updatedAt").descending()));
        return ApiResponse.ok(result, traceId);
    }
}
