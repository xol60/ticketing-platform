package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by saga-orchestrator to order-service when a price change
 * requires user confirmation. Order-service sets status = PRICE_CHANGED.
 */
@Getter @Setter @NoArgsConstructor
public class OrderPriceChangedEvent extends DomainEvent {
    private String     orderId;
    private String     userId;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private Instant    confirmExpiresAt;

    public OrderPriceChangedEvent(String traceId, String sagaId,
                                  String orderId, String userId,
                                  BigDecimal oldPrice, BigDecimal newPrice,
                                  Instant confirmExpiresAt) {
        super(traceId, sagaId);
        this.orderId          = orderId;
        this.userId           = userId;
        this.oldPrice         = oldPrice;
        this.newPrice         = newPrice;
        this.confirmExpiresAt = confirmExpiresAt;
    }
}
