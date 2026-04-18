package com.ticketing.common.exception;

public class TicketUnavailableException extends TicketingException {
    public TicketUnavailableException(String ticketId) {
        super(ErrorCode.TICKET_UNAVAILABLE, "Ticket " + ticketId + " is not available");
    }
}
