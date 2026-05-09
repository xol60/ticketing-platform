package com.ticketing.reservation.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "reservations",
        indexes = {
                @Index(name = "idx_reservations_ticket_status", columnList = "ticket_id, status"),
                @Index(name = "idx_reservations_user_ticket",   columnList = "user_id, ticket_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    /** Absolute deadline — records older than this are expired by the scheduled job. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Set when status transitions to PROMOTED.
     * The watchdog uses this to advance the queue if the promoted user
     * does not place an order before this deadline.
     * Null for non-PROMOTED records.
     */
    @Column(name = "promote_expires_at")
    private Instant promoteExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
