package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class SagaCompensateEvent extends DomainEvent {
    private String failedStep;
    private String orderId;
    private String ticketId;
    private String reason;

    public SagaCompensateEvent(String traceId, String sagaId,
                               String failedStep, String orderId,
                               String ticketId, String reason) {
        super(traceId, sagaId);
        this.failedStep = failedStep;
        this.orderId    = orderId;
        this.ticketId   = ticketId;
        this.reason     = reason;
    }
}
