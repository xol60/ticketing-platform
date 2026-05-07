package com.ticketing.payment.domain.model;

public enum PaymentStatus {
    /** Charge command received; gateway call not yet returned. */
    PENDING,

    /**
     * A {@link com.ticketing.common.events.PaymentCancelCommand} arrived while the
     * gateway call was still in flight. When the gateway eventually responds with
     * success, the payment service will call {@code gateway.refund()} and transition
     * to {@link #REFUNDED} instead of {@link #SUCCESS}.
     */
    CANCELLATION_REQUESTED,

    /** Gateway returned success; money has moved. */
    SUCCESS,

    /** Gateway returned success but was subsequently refunded. */
    REFUNDED,

    /** All retry attempts exhausted; no charge was made. */
    FAILED
}
