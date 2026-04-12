package com.ticketing.saga.model;

public enum SagaStatus {
    STARTED,
    TICKET_RESERVED,
    PRICING_LOCKED,
    PAYMENT_CHARGED,
    COMPLETED,
    COMPENSATING,
    FAILED
}
