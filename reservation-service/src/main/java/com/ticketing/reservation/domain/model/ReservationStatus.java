package com.ticketing.reservation.domain.model;

public enum ReservationStatus {
    QUEUED,
    PROMOTED,
    /** Terminal: the promoted user successfully purchased the ticket. */
    PURCHASED,
    EXPIRED,
    CANCELLED
}
