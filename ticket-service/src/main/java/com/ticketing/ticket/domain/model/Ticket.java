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

    public void reserve(String orderId, String userId, BigDecimal price) {
        this.status          = TicketStatus.RESERVED;
        this.lockedByOrderId = orderId;
        this.lockedByUserId  = userId;
        this.lockedPrice     = price;
        this.reservedAt      = Instant.now();
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
    }
}
