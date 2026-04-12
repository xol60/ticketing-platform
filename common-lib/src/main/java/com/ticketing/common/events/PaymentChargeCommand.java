package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class PaymentChargeCommand extends DomainEvent {
    private String     orderId;
    private String     userId;
    private String     ticketId;
    private BigDecimal amount;

    public PaymentChargeCommand(String traceId, String sagaId,
                                String orderId, String userId,
                                String ticketId, BigDecimal amount) {
        super(traceId, sagaId);
        this.orderId  = orderId;
        this.userId   = userId;
        this.ticketId = ticketId;
        this.amount   = amount;
    }
}
