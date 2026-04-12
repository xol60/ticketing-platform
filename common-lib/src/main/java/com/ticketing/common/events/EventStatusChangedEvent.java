package com.ticketing.common.events;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
public class EventStatusChangedEvent extends DomainEvent {
    private String  eventId;
    private String  name;
    private String  status;       // DRAFT | OPEN | SALES_CLOSED | CANCELLED | COMPLETED
    private Instant salesOpenAt;
    private Instant salesCloseAt;
    private Instant eventDate;

    public EventStatusChangedEvent(String traceId, String sagaId,
                                   String eventId, String name, String status,
                                   Instant salesOpenAt, Instant salesCloseAt,
                                   Instant eventDate) {
        super(traceId, sagaId);
        this.eventId      = eventId;
        this.name         = name;
        this.status       = status;
        this.salesOpenAt  = salesOpenAt;
        this.salesCloseAt = salesCloseAt;
        this.eventDate    = eventDate;
    }
}
