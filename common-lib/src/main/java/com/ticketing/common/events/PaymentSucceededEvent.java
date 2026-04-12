package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class PaymentSucceededEvent extends DomainEvent {
    private String orderId;
    private String userId;
    private BigDecimal amount;
    private String paymentReference;

    public PaymentSucceededEvent(String traceId, String sagaId,
                                 String orderId, String userId,
                                 BigDecimal amount, String paymentReference) {
        super(traceId, sagaId);
        this.orderId          = orderId;
        this.userId           = userId;
        this.amount           = amount;
        this.paymentReference = paymentReference;
    }
}
