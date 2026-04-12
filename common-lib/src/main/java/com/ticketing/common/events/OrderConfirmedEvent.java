package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class OrderConfirmedEvent extends DomainEvent {
    private String     orderId;
    private String     userId;
    private String     ticketId;
    private BigDecimal finalPrice;
    private String     paymentReference;

    public OrderConfirmedEvent(String traceId, String sagaId,
                               String orderId, String userId,
                               String ticketId, BigDecimal finalPrice,
                               String paymentReference) {
        super(traceId, sagaId);
        this.orderId          = orderId;
        this.userId           = userId;
        this.ticketId         = ticketId;
        this.finalPrice       = finalPrice;
        this.paymentReference = paymentReference;
    }
}
