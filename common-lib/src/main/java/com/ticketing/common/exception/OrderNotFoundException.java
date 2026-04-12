package com.ticketing.common.exception;

public class OrderNotFoundException extends TicketingException {
    public OrderNotFoundException(String orderId) {
        super("ORDER_NOT_FOUND", "Order " + orderId + " not found");
    }
}
