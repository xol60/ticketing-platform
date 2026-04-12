package com.ticketing.saga.model;

public enum SagaStatus {
    STARTED,
    TICKET_RESERVED,
    AWAITING_PRICE_CONFIRMATION,  // paused — waiting for user confirm/cancel
    PRICING_LOCKED,
    PAYMENT_CHARGED,
    COMPLETED,
    COMPENSATING,
    CANCELLED,                    // user-initiated cancellation (price rejected)
    FAILED
}
