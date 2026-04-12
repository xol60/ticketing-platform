package com.ticketing.payment.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String orderId) {
        super("Payment not found for orderId: " + orderId);
    }
}
