package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class PriceUpdatedEvent extends DomainEvent {
    private String     eventId;
    private String     ticketId;
    private BigDecimal newPrice;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;

    public PriceUpdatedEvent(String traceId, String sagaId,
                             String eventId, String ticketId,
                             BigDecimal newPrice, BigDecimal minPrice, BigDecimal maxPrice) {
        super(traceId, sagaId);
        this.eventId  = eventId;
        this.ticketId = ticketId;
        this.newPrice = newPrice;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
    }
}
