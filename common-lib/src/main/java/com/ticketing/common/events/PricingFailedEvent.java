package com.ticketing.common.events;

import lombok.*;

/**
 * Published by pricing-service when userPrice was never in price_history
 * (fabricated/manipulated price). Saga must compensate immediately.
 */
@Getter @Setter @NoArgsConstructor
public class PricingFailedEvent extends DomainEvent {
    private String orderId;
    private String ticketId;
    private String reason;

    public PricingFailedEvent(String traceId, String sagaId,
                              String orderId, String ticketId, String reason) {
        super(traceId, sagaId);
        this.orderId  = orderId;
        this.ticketId = ticketId;
        this.reason   = reason;
    }
}
