package com.ticketing.ticket.domain.model;

public enum EventStatus {
    DRAFT,        // created but not yet published
    OPEN,         // tickets on sale
    SALES_CLOSED, // sales window ended, event not yet happened
    CANCELLED,    // event cancelled
    COMPLETED     // event has occurred
}
