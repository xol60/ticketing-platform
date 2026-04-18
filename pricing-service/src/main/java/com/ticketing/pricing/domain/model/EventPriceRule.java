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

    /**
     * Current surge multiplier applied to each ticket's facePrice.
     * 1.0 = no surge, 1.5 = 50% surge.
     * Effective price = ticket.facePrice * surgeMultiplier.
     */
    @Column(name = "surge_multiplier", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal surgeMultiplier = BigDecimal.ONE;

    /**
     * Maximum allowed surge multiplier (e.g. 2.0 = price can at most double).
     */
    @Column(name = "max_surge", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal maxSurge = new BigDecimal("1.5");

    /**
     * Demand factor: soldTickets / totalTickets (0.0 → 1.0).
     * Updated by Kafka listeners when tickets are reserved/released.
     */
    @Column(name = "demand_factor", nullable = false)
    @Builder.Default
    private double demandFactor = 0.0;

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
