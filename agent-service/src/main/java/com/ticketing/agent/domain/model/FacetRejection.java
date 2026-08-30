package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A facet the extractor produced and validation refused.
 *
 * <p>A separate table, not a flag on {@link EventFacet}. Rejected facets must
 * be impossible to select by accident: one forgotten predicate in one query
 * would put fabricated content back into the vector space, and it would fail
 * silently — the results would simply get worse.
 *
 * <p>Kept rather than discarded because this is the only honest measure of how
 * much the local model is making up. Without these rows, a prompt change that
 * doubles the fabrication rate looks identical to one that found fewer
 * facets, and the review queue absorbs the difference invisibly.
 */
@Entity
@Table(name = "facet_rejection")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FacetRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    /** Stored exactly as emitted, so a prompt change can be replayed against real failures. */
    private String dim;

    @Column(columnDefinition = "TEXT")
    private String value;

    @Column(name = "source_span", columnDefinition = "TEXT")
    private String sourceSpan;

    /** {@link com.ticketing.agent.validation.RejectionReason} name. */
    @Column(nullable = false)
    private String reason;

    /** Human-readable specifics — which fact it contradicted, how far short the overlap fell. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }
}
