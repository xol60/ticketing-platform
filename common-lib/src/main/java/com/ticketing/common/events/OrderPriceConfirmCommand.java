package com.ticketing.common.events;

import lombok.*;

/**
 * Published by order-service to saga-orchestrator when the user
 * explicitly confirms the new price.
 */
@Getter @Setter @NoArgsConstructor
public class OrderPriceConfirmCommand extends DomainEvent {
    private String orderId;
    private String userId;

    public OrderPriceConfirmCommand(String traceId, String sagaId,
                                    String orderId, String userId) {
        super(traceId, sagaId);
        this.orderId = orderId;
        this.userId  = userId;
    }
}
