package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class OrderFailedEvent extends DomainEvent {
    private String orderId;
    private String userId;
    private String ticketId;
    private String reason;

    public OrderFailedEvent(String traceId, String sagaId,
                            String orderId, String userId,
                            String ticketId, String reason) {
        super(traceId, sagaId);
        this.orderId  = orderId;
        this.userId   = userId;
        this.ticketId = ticketId;
        this.reason   = reason;
    }
}
