package com.ticketing.common.events;

import lombok.*;

/**
 * Published by saga-orchestrator to order-service after a user-initiated
 * cancellation (price rejected). Distinct from ORDER_FAILED (system error).
 */
@Getter @Setter @NoArgsConstructor
public class OrderCancelledEvent extends DomainEvent {
    private String orderId;
    private String userId;
    private String ticketId;
    private String reason;

    public OrderCancelledEvent(String traceId, String sagaId,
                               String orderId, String userId,
                               String ticketId, String reason) {
        super(traceId, sagaId);
        this.orderId  = orderId;
        this.userId   = userId;
        this.ticketId = ticketId;
        this.reason   = reason;
    }
}
