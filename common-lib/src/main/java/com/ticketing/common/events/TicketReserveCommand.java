package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class TicketReserveCommand extends DomainEvent {
    private String ticketId;
    private String orderId;
    private String userId;

    public TicketReserveCommand(String traceId, String sagaId,
                                String ticketId, String orderId, String userId) {
        super(traceId, sagaId);
        this.ticketId = ticketId;
        this.orderId  = orderId;
        this.userId   = userId;
    }
}
