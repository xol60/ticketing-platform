package com.ticketing.common.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic outbox row. Each service that publishes Kafka events extends this
 * with a concrete {@code @Entity @Table("outbox_events")} class — JPA can't
 * map a {@code @MappedSuperclass} directly as an entity, so the subclass adds
 * only the table mapping while inheriting all the fields and behaviour.
 *
 * <h3>Hybrid outbox semantics</h3>
 * Rows are persisted only when the synchronous Kafka publish attempt fails
 * (timeout, broker unavailable, serialisation error). A scheduled worker then
 * drains the table by retrying each row.
 *
 * <p>Healthy state: zero rows. The partial index
 * {@code idx_outbox_unpublished WHERE published_at IS NULL} keeps drain-worker
 * scans free under nominal load.
 */
@Getter
@Setter
@NoArgsConstructor
@MappedSuperclass
public abstract class OutboxEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public boolean isUnpublished() {
        return publishedAt == null;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void recordRetry(String error) {
        this.retryCount += 1;
        this.lastError = error;
    }
}
