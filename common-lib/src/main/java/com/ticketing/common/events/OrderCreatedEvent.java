package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
public class OrderCreatedEvent extends DomainEvent {
    private String     orderId;
    private String     userId;
    private String     ticketId;
    /** Price the user saw on screen and agreed to pay. Never trusted blindly — validated via price_history. */
    private BigDecimal userPrice;
    /** DB-stamped creation time of the order, used for point-in-time price history lookup. */
    private Instant    orderCreatedAt;

    public OrderCreatedEvent(String traceId, String sagaId,
                             String orderId, String userId,
                             String ticketId, BigDecimal userPrice,
                             Instant orderCreatedAt) {
        super(traceId, sagaId);
        this.orderId        = orderId;
        this.userId         = userId;
        this.ticketId       = ticketId;
        this.userPrice      = userPrice;
        this.orderCreatedAt = orderCreatedAt;
    }
}
