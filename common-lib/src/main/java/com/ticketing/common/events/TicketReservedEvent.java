package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class TicketReservedEvent extends DomainEvent {
    private String ticketId;
    private String orderId;
    private String userId;
    private String eventId;
    private BigDecimal lockedPrice;

    public TicketReservedEvent(String traceId, String sagaId,
                               String ticketId, String orderId,
                               String userId, String eventId, BigDecimal lockedPrice) {
        super(traceId, sagaId);
        this.ticketId    = ticketId;
        this.orderId     = orderId;
        this.userId      = userId;
        this.eventId     = eventId;
        this.lockedPrice = lockedPrice;
    }
}
