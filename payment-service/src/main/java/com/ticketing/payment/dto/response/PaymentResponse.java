package com.ticketing.payment.dto.response;

import com.ticketing.payment.domain.model.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class PaymentResponse {

    private UUID id;
    private String orderId;
    private String userId;
    private String ticketId;
    private BigDecimal amount;
    private PaymentStatus status;
    private String paymentReference;
    private String failureReason;
    private int attemptCount;
    private Instant createdAt;
    private Instant updatedAt;
}
