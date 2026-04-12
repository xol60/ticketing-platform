package com.ticketing.secondary.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "listings",
    indexes = {
        @Index(name = "idx_listings_event_status",  columnList = "event_id, status"),
        @Index(name = "idx_listings_seller",        columnList = "seller_id"),
        @Index(name = "idx_listings_ticket",        columnList = "ticket_id")
    }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Listing {

    @Id
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    @Column(name = "seller_id", nullable = false, length = 36)
    private String sellerId;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "ask_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal askPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status;

    @Column(name = "purchased_by_user_id", length = 36)
    private String purchasedByUserId;

    @Column(name = "purchased_order_id", length = 36)
    private String purchasedOrderId;

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
        if (this.id == null) this.id = UUID.randomUUID().toString();
        if (this.status == null) this.status = ListingStatus.ACTIVE;
    }
}
