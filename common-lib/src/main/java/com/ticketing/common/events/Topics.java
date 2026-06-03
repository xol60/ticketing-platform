package com.ticketing.common.events;

public final class Topics {

    private Topics() {}

    // Ticket domain — commands
    //
    // ALL commands to the ticket service use a single topic keyed by orderId.
    // TicketReserveCommand, TicketConfirmCommand, and TicketReleaseCommand for the
    // same order always land on the same partition → consumed sequentially by one thread.
    // Split topics would allow a release command to be processed before a confirm command,
    // causing the confirm to find an AVAILABLE ticket and publish a spurious failure event.
    public static final String TICKET_CMD            = "ticket.cmd";

    // Ticket domain — events (read by saga-orchestrator and other consumers)
    public static final String TICKET_RESERVED       = "ticket.reserved";
    public static final String TICKET_RELEASED       = "ticket.released";
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
    public static final String PRICING_LOCK_CMD      = "pricing.lock.cmd";
    public static final String PRICING_LOCKED        = "pricing.locked";
    public static final String PRICING_PRICE_CHANGED = "pricing.price.changed";
    public static final String PRICING_FAILED        = "pricing.failed";
    public static final String PRICE_UPDATED         = "price.updated";

    // Payment domain — commands
    //
    // ALL commands to the payment service use a single topic keyed by orderId.
    // This guarantees that PaymentChargeCommand and PaymentCancelCommand for the
    // same order always land on the same partition and are consumed sequentially.
    // Two separate topics (payment.charge.cmd / payment.cancel.cmd) would allow
    // the cancel consumer to process its message before the charge consumer does,
    // silently dropping the cancellation and leaving the customer charged.
    public static final String PAYMENT_CMD           = "payment.cmd";

    // Payment domain — events (read by saga-orchestrator and other consumers)
    public static final String PAYMENT_SUCCEEDED     = "payment.succeeded";
    public static final String PAYMENT_FAILED        = "payment.failed";
    public static final String PAYMENT_REFUNDED      = "payment.refunded";
    public static final String PAYMENT_DLQ           = "payment.dlq";

    // Reservation
    public static final String RESERVATION_PROMOTED  = "reservation.promoted";

    // Event lifecycle
    public static final String EVENT_STATUS_CHANGED  = "event.status.changed";

    // Event search index — carries full metadata for the Elasticsearch derived index.
    //
    // Published by ticket-service whenever an Event is created, has its metadata
    // edited, or transitions status. Consumed by search-service which upserts the
    // ES document (status == OPEN) or deletes it (any other terminal/closed status).
    //
    // Keyed by eventId so all updates for one event land on the same partition and
    // are applied to ES in order — no risk of a stale upsert overtaking a delete.
    public static final String EVENT_SEARCH_INDEXED  = "event.search.indexed";

    // Event-hotness signal — published by ticket-service's EventHotnessWatchdog
    // on transition (HOT_ENTER threshold crossed up → publish hot=true; HOT_EXIT
    // threshold crossed down → publish hot=false). NOT a per-tick stream — only
    // transitions, so traffic is minimal. Consumed by order-service for cache
    // pre-warm signals; future consumers (admin observability) can subscribe
    // without changes here. Keyed by eventId so per-event transitions are
    // strictly ordered within a partition.
    public static final String EVENT_HOTNESS_CHANGED = "event.hotness.changed";

    // Notification
    public static final String NOTIFICATION_SEND     = "notification.send";

    // Security
    public static final String AUTH_SECURITY_ALERT   = "auth.security.alert";
}
