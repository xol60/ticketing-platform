package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The filterable projection of an event, rebuilt from every
 * {@code event.search.indexed} message.
 *
 * <p>Not a mirror of {@code ticket_db.events} — only the columns the hard
 * filter and the compare projection read. Anything the agent cannot filter or
 * display is left behind on purpose: this table is scanned on every turn, and
 * ticket-service remains one HTTP call away for canonical detail.
 *
 * <h3>{@code searchable} is not {@code status}</h3>
 * {@link #status} is the business lifecycle, owned by ticket-service.
 * {@link #searchable} is a curation gate, owned here: it goes true only once a
 * human has accepted this event's facets. An OPEN event with unreviewed facets
 * stays invisible to the agent, which is correct — recommending an event on
 * the strength of facets nobody checked is worse than not recommending it.
 */
@Entity
@Table(name = "agent_event")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentEvent {

    /** eventId from ticket-service — same value, so the two systems join without a mapping table. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "primary_artist")
    private String primaryArtist;

    @Column(name = "venue_name")
    private String venueName;

    /** Raw text as ticket-service holds it, kept for display and for re-resolution. */
    @Column(name = "venue_city")
    private String venueCity;

    /**
     * Resolved city. The hard filter compares this integer, never the text —
     * a city inside an embedding matches any description that merely mentions
     * the place.
     */
    @Column(name = "city_id")
    private Integer cityId;

    private String category;
    private String genre;

    @Column(nullable = false)
    private String status;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "sales_open_at")
    private Instant salesOpenAt;

    @Column(name = "sales_close_at")
    private Instant salesCloseAt;

    /** MIN/MAX over the event's ticket face prices, computed by ticket-service. */
    @Column(name = "price_min")
    private BigDecimal priceMin;

    @Column(name = "price_max")
    private BigDecimal priceMax;

    /** small | medium | large — derived from ticket count, not from wording. */
    @Column(name = "capacity_band")
    private String capacityBand;

    /** Source text the facets were distilled from, so a reviewer can check provenance. */
    @Column(name = "description_raw", columnDefinition = "TEXT")
    private String descriptionRaw;

    @Column(nullable = false)
    private boolean searchable;

    /**
     * Facets that survived validation on the most recent extraction, and those
     * that did not.
     *
     * <p>Denormalised from {@code facet_rejection} so the review UI can show
     * the ratio per row without an aggregate, and so a pathological event
     * stands out at a glance. The ratio is the diagnostic that matters: an
     * event with 1 kept and 9 rejected is not an event with few facets, it is
     * an event the extractor mostly invented.
     */
    @Column(name = "facets_kept", nullable = false)
    @Builder.Default
    private int facetsKept = 0;

    @Column(name = "facets_rejected", nullable = false)
    @Builder.Default
    private int facetsRejected = 0;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

    /** Which embedding model produced this event's facet vectors. Drives re-embed cutover. */
    /** Which extraction prompt produced this event's facets. See IngestionExtractor. */
    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
