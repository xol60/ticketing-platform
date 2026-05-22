package com.ticketing.common.events;

/**
 * Lifecycle status of an event.
 *
 * <p>Lives in {@code common-lib} so that both producers (ticket-service, source of truth)
 * and consumers (search-service and any future subscribers) compare against the same
 * named constants instead of bare string literals.
 *
 * <p>The {@code EventSearchIndexedEvent} and {@code EventStatusChangedEvent} payloads
 * still carry {@code status} as a {@code String} (not the enum) so a producer running an
 * older version that doesn't know a newly-added status doesn't crash the consumer's
 * deserializer. Consumers should still compare via {@code EventStatus.OPEN.name()} —
 * unknown statuses are treated as "not OPEN" and trigger a delete from the search index.
 */
public enum EventStatus {
    DRAFT,        // created but not yet published
    OPEN,         // tickets on sale
    SALES_CLOSED, // sales window ended, event not yet happened
    CANCELLED,    // event cancelled
    COMPLETED     // event has occurred
}
