package com.ticketing.common.events;

import lombok.*;

import java.time.Instant;

@Getter @Setter @NoArgsConstructor
public class FlashSaleEvent extends DomainEvent {
    private String  eventId;
    private String  eventName;
    private int     totalTickets;
    private Instant saleStartTime;
    private Instant saleEndTime;

    public FlashSaleEvent(String traceId, String sagaId,
                          String eventId, String eventName,
                          int totalTickets, Instant saleStartTime, Instant saleEndTime) {
        super(traceId, sagaId);
        this.eventId       = eventId;
        this.eventName     = eventName;
        this.totalTickets  = totalTickets;
        this.saleStartTime = saleStartTime;
        this.saleEndTime   = saleEndTime;
    }
}
