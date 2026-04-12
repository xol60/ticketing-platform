package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class PaymentFailedEvent extends DomainEvent {
    private String orderId;
    private String userId;
    private String failureReason;
    private int    attemptCount;

    public PaymentFailedEvent(String traceId, String sagaId,
                              String orderId, String userId,
                              String failureReason, int attemptCount) {
        super(traceId, sagaId);
        this.orderId       = orderId;
        this.userId        = userId;
        this.failureReason = failureReason;
        this.attemptCount  = attemptCount;
    }
}
