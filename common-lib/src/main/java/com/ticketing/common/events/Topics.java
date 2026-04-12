package com.ticketing.common.events;

public final class Topics {

    private Topics() {}

    // Ticket domain
    public static final String TICKET_RESERVE_CMD   = "ticket.reserve.cmd";
    public static final String TICKET_RESERVED       = "ticket.reserved";
    public static final String TICKET_RELEASE_CMD   = "ticket.release.cmd";
    public static final String TICKET_RELEASED       = "ticket.released";
    public static final String TICKET_CONFIRM_CMD   = "ticket.confirm.cmd";
    public static final String TICKET_CONFIRMED      = "ticket.confirmed";

    // Order domain
    public static final String ORDER_CREATED         = "order.created";
    public static final String ORDER_CONFIRMED       = "order.confirmed";
    public static final String ORDER_FAILED          = "order.failed";
    public static final String ORDER_CANCELLED       = "order.cancelled";
    public static final String ORDER_PRICE_CHANGED   = "order.price.changed";
    public static final String ORDER_PRICE_CONFIRM   = "order.price.confirm";
    public static final String ORDER_PRICE_CANCEL    = "order.price.cancel";

    // Pricing domain
    public static final String PRICING_LOCK_CMD     = "pricing.lock.cmd";
    public static final String PRICING_LOCKED        = "pricing.locked";
    public static final String PRICING_UNLOCK_CMD   = "pricing.unlock.cmd";
    public static final String PRICING_PRICE_CHANGED = "pricing.price.changed";
    public static final String PRICING_FAILED        = "pricing.failed";
    public static final String PRICE_UPDATED         = "price.updated";

    // Payment domain
    public static final String PAYMENT_CHARGE_CMD   = "payment.charge.cmd";
    public static final String PAYMENT_SUCCEEDED     = "payment.succeeded";
    public static final String PAYMENT_FAILED        = "payment.failed";
    public static final String PAYMENT_DLQ           = "payment.dlq";

    // Saga
    public static final String SAGA_COMPENSATE       = "saga.compensate";

    // Reservation
    public static final String RESERVATION_PROMOTED  = "reservation.promoted";

    // Flash sale
    public static final String SALE_FLASH            = "sale.flash";

    // Event lifecycle
    public static final String EVENT_STATUS_CHANGED = "event.status.changed";

    // Notification
    public static final String NOTIFICATION_SEND     = "notification.send";
}
