package com.ticketing.pricing.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_price_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventPriceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", unique = true, nullable = false)
    private String eventId;

    @Column(name = "event_name")
    private String eventName;

    @Column(name = "min_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "current_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentPrice;

    /**
     * Demand multiplier used in dynamic pricing. Default 1.0 (neutral).
     * Higher demand → higher factor → higher currentPrice.
     */
    @Column(name = "demand_factor", nullable = false)
    @Builder.Default
    private double demandFactor = 1.0;

    @Column(name = "total_tickets", nullable = false)
    @Builder.Default
    private int totalTickets = 0;

    @Column(name = "sold_tickets", nullable = false)
    @Builder.Default
    private int soldTickets = 0;

    /** Date/time of the event itself, used for time-to-event pricing factor. */
    @Column(name = "event_date")
    private Instant eventDate;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
