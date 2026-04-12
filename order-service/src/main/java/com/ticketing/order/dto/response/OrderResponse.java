package com.ticketing.order.dto.response;

import com.ticketing.order.domain.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {

    private String id;
    private String userId;
    private String ticketId;
    private String sagaId;
    private OrderStatus status;
    private BigDecimal requestedPrice;
    private BigDecimal finalPrice;
    private String paymentReference;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
}
