package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class TicketConfirmCommand extends DomainEvent {
    private String ticketId;
    private String orderId;

    public TicketConfirmCommand(String traceId, String sagaId,
                                String ticketId, String orderId) {
        super(traceId, sagaId);
        this.ticketId = ticketId;
        this.orderId  = orderId;
    }
}
