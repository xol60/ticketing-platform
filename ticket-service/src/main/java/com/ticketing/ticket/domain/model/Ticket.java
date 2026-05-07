package com.ticketing.ticket.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "tickets",
    indexes = {
        @Index(name = "idx_tickets_event_id",     columnList = "event_id"),
        @Index(name = "idx_tickets_status",        columnList = "status"),
        @Index(name = "idx_tickets_event_status",  columnList = "event_id, status"),
        @Index(name = "idx_tickets_order_id",      columnList = "locked_by_order_id")
    }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_name", nullable = false, length = 255)
    private String eventName;

    @Column(name = "section", length = 100)
    private String section;

    @Column(name = "row", length = 20)
    private String row;

    @Column(name = "seat", nullable = false, length = 20)
    private String seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "face_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal facePrice;

    @Column(name = "locked_price", precision = 10, scale = 2)
    private BigDecimal lockedPrice;

    @Column(name = "locked_by_order_id", length = 36)
    private String lockedByOrderId;

    @Column(name = "locked_by_user_id", length = 36)
    private String lockedByUserId;

    @Column(name = "reserved_at")
    private Instant reservedAt;

    /**
     * Explicit deadline set at reservation time.
     * The stuck-reservation watchdog releases this ticket only after this instant
     * has passed — never while the saga still has budget to complete its payment.
     * The service layer sets this to {@code now + RESERVATION_TIMEOUT (120 s)} so
     * that the worst-case saga (30 s price-confirm + 30 s payment + Kafka overhead)
     * always finishes well within the window.
     */
    @Column(name = "reserved_until")
    private Instant reservedUntil;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.status == null) {
            this.status = TicketStatus.AVAILABLE;
        }
    }

    public boolean isAvailable() {
        return status == TicketStatus.AVAILABLE;
    }

    /**
     * @param reservedUntil deadline after which the watchdog may release this ticket;
     *                      set by the caller to {@code now + saga-total-timeout}.
     */
    public void reserve(String orderId, String userId, BigDecimal price, Instant reservedUntil) {
        this.status          = TicketStatus.RESERVED;
        this.lockedByOrderId = orderId;
        this.lockedByUserId  = userId;
        this.lockedPrice     = price;
        this.reservedAt      = Instant.now();
        this.reservedUntil   = reservedUntil;
    }

    public void confirm(String orderId) {
        this.status       = TicketStatus.CONFIRMED;
        this.confirmedAt  = Instant.now();
    }

    public void release() {
        this.status          = TicketStatus.AVAILABLE;
        this.lockedByOrderId = null;
        this.lockedByUserId  = null;
        this.lockedPrice     = null;
        this.reservedAt      = null;
        this.reservedUntil   = null;
    }
}
