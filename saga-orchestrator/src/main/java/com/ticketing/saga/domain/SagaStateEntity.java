package com.ticketing.saga.domain;

import com.ticketing.saga.model.SagaStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity that persists saga state to Postgres.
 *
 * stateJson — full SagaState serialised as JSON (TEXT column).
 *             Avoids a complex column-per-field mapping and keeps the entity
 *             independent of future SagaState fields.
 *
 * status    — denormalised from stateJson so the watchdog can query
 *             non-terminal sagas efficiently without parsing JSON.
 */
@Entity
@Table(
    name = "saga_states",
    indexes = @Index(name = "idx_saga_states_status", columnList = "status")
)
@Getter
@Setter
@NoArgsConstructor
public class SagaStateEntity {

    @Id
    @Column(name = "saga_id", length = 36, nullable = false, updatable = false)
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaStatus status;

    /** Full saga state serialised as JSON. */
    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
