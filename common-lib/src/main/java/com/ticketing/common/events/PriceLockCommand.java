package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
public class PriceLockCommand extends DomainEvent {
    private String     ticketId;
    private String     orderId;
    private String     eventId;
    /** Price the user submitted. Used for history validation. */
    private BigDecimal userPrice;
    /** DB timestamp of when the order was created; used for point-in-time lookup. */
    private Instant    orderCreatedAt;
    /**
     * True when the user has already confirmed a price change.
     * When true, pricing-service skips all validation and locks at the current price.
     */
    private boolean    confirmed;

    public PriceLockCommand(String traceId, String sagaId,
                            String ticketId, String orderId, String eventId,
                            BigDecimal userPrice, Instant orderCreatedAt, boolean confirmed) {
        super(traceId, sagaId);
        this.ticketId       = ticketId;
        this.orderId        = orderId;
        this.eventId        = eventId;
        this.userPrice      = userPrice;
        this.orderCreatedAt = orderCreatedAt;
        this.confirmed      = confirmed;
    }
}
