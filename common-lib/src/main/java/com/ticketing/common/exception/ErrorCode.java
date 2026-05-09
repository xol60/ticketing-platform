package com.ticketing.common.exception;

public enum ErrorCode {

    // ── Generic ───────────────────────────────────────────────────────────────
    VALIDATION_ERROR(400),
    INTERNAL_ERROR(500),
    RESOURCE_NOT_FOUND(404),
    DUPLICATE_RESOURCE(409),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    MISSING_HEADER(400),

    // ── Ticket domain ─────────────────────────────────────────────────────────
    TICKET_NOT_FOUND(404),
    TICKET_UNAVAILABLE(409),
    TICKET_LOCK_CONFLICT(409),
    EVENT_NOT_OPEN(409),
    EVENT_NOT_FOUND(404),

    // ── Order domain ──────────────────────────────────────────────────────────
    ORDER_NOT_FOUND(404),
    ORDER_INVALID_STATUS(409),

    // ── Pricing domain ────────────────────────────────────────────────────────
    INVALID_PRICE(400),
    NO_PRICE_RULE(404),
    PRICE_CONFIRMATION_TIMEOUT(408),

    // ── Payment domain ────────────────────────────────────────────────────────
    PAYMENT_NOT_FOUND(404),

    // ── Auth domain ───────────────────────────────────────────────────────────
    AUTH_INVALID_CREDENTIALS(401),
    AUTH_ACCOUNT_DISABLED(403),
    AUTH_ACCOUNT_LOCKED(423),
    AUTH_TOKEN_EXPIRED(401),
    AUTH_TOKEN_INVALID(401),
    AUTH_EMAIL_TAKEN(409),
    AUTH_USERNAME_TAKEN(409),

    // ── Saga domain ───────────────────────────────────────────────────────────
    SAGA_ERROR(500),
    SAGA_NOT_FOUND(404),
    SAGA_COMPENSATION(500),
    SAGA_STUCK(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() { return httpStatus; }
}
