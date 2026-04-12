package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by pricing-service when userPrice was real (found in price_history)
 * but no longer current. Saga pauses and asks the user to confirm or cancel.
 */
@Getter @Setter @NoArgsConstructor
public class PriceChangedEvent extends DomainEvent {
    private String     orderId;
    private String     ticketId;
    private BigDecimal oldPrice;          // what user submitted
    private BigDecimal newPrice;          // current effective price
    private Instant    confirmExpiresAt;  // user must confirm before this

    public PriceChangedEvent(String traceId, String sagaId,
                             String orderId, String ticketId,
                             BigDecimal oldPrice, BigDecimal newPrice,
                             Instant confirmExpiresAt) {
        super(traceId, sagaId);
        this.orderId          = orderId;
        this.ticketId         = ticketId;
        this.oldPrice         = oldPrice;
        this.newPrice         = newPrice;
        this.confirmExpiresAt = confirmExpiresAt;
    }
}
