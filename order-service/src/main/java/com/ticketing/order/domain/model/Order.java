package com.ticketing.order.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /** Price the user submitted — stored for audit. Never used as the actual charge amount. */
    @Column(name = "requested_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedPrice;

    /** New price shown to user during PRICE_CHANGED state, awaiting confirmation. */
    @Column(name = "pending_price", precision = 19, scale = 4)
    private BigDecimal pendingPrice;

    @Column(name = "final_price", precision = 19, scale = 4)
    private BigDecimal finalPrice;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
