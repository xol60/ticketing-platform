package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class PriceLockCommand extends DomainEvent {
    private String     ticketId;
    private String     orderId;
    private String     eventId;
    private BigDecimal requestedPrice;

    public PriceLockCommand(String traceId, String sagaId,
                            String ticketId, String orderId,
                            String eventId, BigDecimal requestedPrice) {
        super(traceId, sagaId);
        this.ticketId       = ticketId;
        this.orderId        = orderId;
        this.eventId        = eventId;
        this.requestedPrice = requestedPrice;
    }
}
