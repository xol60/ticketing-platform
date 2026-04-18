package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class TicketReleasedEvent extends DomainEvent {
    private String ticketId;
    private String orderId;
    private String eventId;
    private String reason;

    public TicketReleasedEvent(String traceId, String sagaId,
                               String ticketId, String orderId,
                               String eventId, String reason) {
        super(traceId, sagaId);
        this.ticketId = ticketId;
        this.orderId  = orderId;
        this.eventId  = eventId;
        this.reason   = reason;
    }
}
