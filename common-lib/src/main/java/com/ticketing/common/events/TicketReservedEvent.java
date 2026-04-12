package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class TicketReservedEvent extends DomainEvent {
    private String ticketId;
    private String orderId;
    private String userId;
    private BigDecimal lockedPrice;

    public TicketReservedEvent(String traceId, String sagaId,
                               String ticketId, String orderId,
                               String userId, BigDecimal lockedPrice) {
        super(traceId, sagaId);
        this.ticketId    = ticketId;
        this.orderId     = orderId;
        this.userId      = userId;
        this.lockedPrice = lockedPrice;
    }
}
