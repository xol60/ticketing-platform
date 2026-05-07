package com.ticketing.common.events;

import lombok.*;

import java.math.BigDecimal;

/**
 * Published by the payment service after a successful refund.
 * Used for audit, notifications, and order-service status updates.
 * By the time this fires, the saga is already in a terminal state (FAILED/CANCELLED).
 */
@Getter @Setter @NoArgsConstructor
public class PaymentRefundedEvent extends DomainEvent {

    private String     orderId;
    private String     userId;
    private BigDecimal amount;
    private String     paymentReference;
    /** Why the refund was issued — e.g. "WATCHDOG_STUCK_RESERVATION", "CONFIRM_FAILED_WRONG_STATE". */
    private String     reason;

    public PaymentRefundedEvent(String traceId, String sagaId,
                                String orderId, String userId,
                                BigDecimal amount, String paymentReference, String reason) {
        super(traceId, sagaId);
        this.orderId          = orderId;
        this.userId           = userId;
        this.amount           = amount;
        this.paymentReference = paymentReference;
        this.reason           = reason;
    }
}
