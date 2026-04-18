package com.ticketing.common.exception;

public class TicketingException extends RuntimeException {

    private final ErrorCode errorCode;

    public TicketingException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode()  { return errorCode; }
    public int       getHttpStatus() { return errorCode.getHttpStatus(); }
}
