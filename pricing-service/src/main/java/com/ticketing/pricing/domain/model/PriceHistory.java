package com.ticketing.pricing.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "price_history",
    indexes = {
        @Index(name = "idx_price_history_event_time",
               columnList = "event_id, valid_from, valid_to")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** NULL means this is the currently active price record. */
    @Column(name = "valid_to")
    private Instant validTo;

    /** MANUAL | DEMAND | FLASH_SALE | SCHEDULE */
    @Column(name = "triggered_by", nullable = false, length = 50)
    private String triggeredBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
