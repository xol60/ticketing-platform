package com.ticketing.order.dto.response;

import com.ticketing.order.domain.model.OrderStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderResponse {
    private String      id;
    private String      userId;
    private String      ticketId;
    private String      sagaId;
    private OrderStatus status;
    private BigDecimal  requestedPrice;
    /** Populated when status = PRICE_CHANGED. The new price awaiting user confirmation. */
    private BigDecimal  pendingPrice;
    private BigDecimal  finalPrice;
    private String      paymentReference;
    private String      failureReason;
    private Instant     createdAt;
    private Instant     updatedAt;
}
