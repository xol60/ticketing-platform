package com.ticketing.common.events;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public abstract class DomainEvent {

    private String eventId;
    private String traceId;
    private String sagaId;
    private Instant occurredAt;
    private int version;

    protected DomainEvent(String traceId, String sagaId) {
        this.eventId    = UUID.randomUUID().toString();
        this.traceId    = traceId;
        this.sagaId     = sagaId;
        this.occurredAt = Instant.now();
        this.version    = 1;
    }
}
