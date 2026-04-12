package com.ticketing.common.exception;

public class TicketingException extends RuntimeException {
    private final String errorCode;

    public TicketingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
