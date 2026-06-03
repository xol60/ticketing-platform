package com.ticketing.common.events;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Published on the {@link Topics#EVENT_HOTNESS_CHANGED} topic each time the
 * watchdog flips an event between HOT and not-HOT (and ONLY on those
 * transitions — not every watchdog tick).
 *
 * <p>An event becomes HOT when the rolling 60-second view counter crosses
 * {@code HOT_ENTER} (default 50 views/minute); it becomes not-HOT when the
 * counter falls below {@code HOT_EXIT} (default 20). The hysteresis prevents
 * flapping when traffic hovers near the boundary. {@code viewsPerMinute}
 * carries the count observed at the moment of transition so consumers can
 * log / dashboard / decide pre-warm scope.
 *
 * <p>Consumers (v1: order-service logs only) should react idempotently —
 * Kafka at-least-once delivery may surface the same transition more than
 * once on a redelivery.
 */
@Getter
@Setter
@NoArgsConstructor
public class EventHotnessChangedEvent extends DomainEvent {

    private String  eventId;
    private boolean hot;
    private long    viewsPerMinute;

    public EventHotnessChangedEvent(String traceId,
                                     String eventId,
                                     boolean hot,
                                     long viewsPerMinute) {
        // No sagaId — hotness is observability, not part of an order saga.
        super(traceId, null);
        this.eventId        = eventId;
        this.hot            = hot;
        this.viewsPerMinute = viewsPerMinute;
    }
}
