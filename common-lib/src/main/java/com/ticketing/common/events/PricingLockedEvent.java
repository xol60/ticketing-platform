package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class PricingLockedEvent extends DomainEvent {
    private String     ticketId;
    private String     orderId;
    private BigDecimal lockedPrice;

    public PricingLockedEvent(String traceId, String sagaId,
                              String ticketId, String orderId, BigDecimal lockedPrice) {
        super(traceId, sagaId);
        this.ticketId    = ticketId;
        this.orderId     = orderId;
        this.lockedPrice = lockedPrice;
    }
}
