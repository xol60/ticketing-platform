package com.ticketing.common.events;

import lombok.*;

/**
 * Published by order-service to saga-orchestrator when the user
 * rejects the new price and wants to cancel.
 */
@Getter @Setter @NoArgsConstructor
public class OrderPriceCancelCommand extends DomainEvent {
    private String orderId;
    private String userId;

    public OrderPriceCancelCommand(String traceId, String sagaId,
                                   String orderId, String userId) {
        super(traceId, sagaId);
        this.orderId = orderId;
        this.userId  = userId;
    }
}
