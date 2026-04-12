package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class OrderCreatedEvent extends DomainEvent {
    private String orderId;
    private String userId;
    private String ticketId;
    private BigDecimal requestedPrice;

    public OrderCreatedEvent(String traceId, String sagaId,
                             String orderId, String userId,
                             String ticketId, BigDecimal requestedPrice) {
        super(traceId, sagaId);
        this.orderId        = orderId;
        this.userId         = userId;
        this.ticketId       = ticketId;
        this.requestedPrice = requestedPrice;
    }
}
