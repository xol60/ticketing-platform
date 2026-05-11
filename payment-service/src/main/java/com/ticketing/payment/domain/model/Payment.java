package com.ticketing.payment.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", unique = true, nullable = false)
    private String orderId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "attempt_count")
    private int attemptCount;

    /**
     * Saga identifier — stored so the retry watchdog can publish
     * {@code PaymentSucceededEvent} / {@code PaymentFailedEvent} with the
     * correct {@code sagaId} the saga-orchestrator uses for correlation.
     */
    @Column(name = "saga_id", length = 36)
    private String sagaId;

    /** Distributed trace identifier; nullable. */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /**
     * When the retry watchdog should next attempt a charge.
     *
     * <ul>
     *   <li>Set to {@code Instant.now()} on initial save → "retry immediately".</li>
     *   <li>Advanced to {@code now + backoff} after each failed attempt.</li>
     *   <li>Set to {@code now + CLAIM_LEASE} by the watchdog before calling the
     *       gateway — prevents concurrent watchdog pods from double-charging.</li>
     *   <li>Set to {@code null} when the payment reaches a terminal status.</li>
     * </ul>
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
