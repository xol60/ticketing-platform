package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class PriceUnlockCommand extends DomainEvent {
    private String ticketId;
    private String orderId;
    private String reason;

    public PriceUnlockCommand(String traceId, String sagaId,
                              String ticketId, String orderId, String reason) {
        super(traceId, sagaId);
        this.ticketId = ticketId;
        this.orderId  = orderId;
        this.reason   = reason;
    }
}
