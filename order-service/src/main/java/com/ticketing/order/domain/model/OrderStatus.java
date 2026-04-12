package com.ticketing.order.domain.model;

public enum OrderStatus {
    PENDING,
    PRICE_CHANGED,   // saga paused — waiting for user to confirm/cancel the new price
    CONFIRMED,
    FAILED,
    CANCELLED        // user explicitly rejected a price change (distinct from system failure)
}
