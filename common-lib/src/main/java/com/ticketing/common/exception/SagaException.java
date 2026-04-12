package com.ticketing.common.exception;

public class SagaException extends TicketingException {
    public SagaException(String sagaId, String message) {
        super("SAGA_ERROR", "Saga " + sagaId + ": " + message);
    }
}
