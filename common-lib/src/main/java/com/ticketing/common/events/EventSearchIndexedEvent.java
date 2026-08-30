package com.ticketing.common.events;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Carries the full searchable payload of an {@code Event} from ticket-service
 * (source of truth) to search-service (Elasticsearch derived index).
 *
 * <p>Published by {@code ticket-service} every time an event is created,
 * its metadata is edited, or its lifecycle status changes. The search-service
 * consumer treats this as the authoritative snapshot for the ES document:
 *
 * <ul>
 *   <li>{@code status == "OPEN"} → upsert the document into the {@code events}
 *       index (full reindex with all fields below).</li>
 *   <li>any other status (DRAFT, SALES_CLOSED, CANCELLED, COMPLETED) →
 *       delete the document so it disappears from public search.</li>
 * </ul>
 *
 * <p>Keyed by {@code eventId} on the Kafka topic so all updates for one event
 * land on the same partition and are applied in producer-send order — no risk
 * of a stale upsert overtaking a delete.
 */
@Getter @Setter @NoArgsConstructor
public class EventSearchIndexedEvent extends DomainEvent {

    private String  eventId;
    private String  name;
    private String  status;          // DRAFT | OPEN | SALES_CLOSED | CANCELLED | COMPLETED
    private Instant salesOpenAt;
    private Instant salesCloseAt;
    private Instant eventDate;

    // ── Searchable metadata (may be null when an event is freshly created
    //   without these fields filled in — search-service handles nulls gracefully) ──
    private String primaryArtist;
    private String venueName;
    private String venueCity;
    private String shortDescription;
    private String fullDescription;
    private String category;
    private String genre;

    // ── Ticket-derived facts ─────────────────────────────────────────────────
    // Aggregates over the event's tickets, computed by ticket-service because
    // it owns them. Consumers must not try to derive these themselves: only
    // ticket-service can see the ticket table, and a consumer guessing at
    // crowd size from prose is the exact failure the agent's validation exists
    // to prevent.
    //
    // All three are null for an event that has no tickets yet, which is a
    // normal state between creating an event and seeding its seats.

    /** Cheapest and dearest face price. Lets search filter and sort by price. */
    private java.math.BigDecimal priceMin;
    private java.math.BigDecimal priceMax;

    /**
     * Total tickets. agent-service turns this into a capacity band and uses it
     * to reject facets claiming an intimate room for a twenty-thousand-seat
     * stadium — a fact beating a claim, rather than two opinions.
     */
    private Integer ticketCount;

    /**
     * Full-arg constructor — matches the call site in
     * {@code ticket-service / EventService.publishSearchIndexed(...)}.
     *
     * <p>Long, and stays a constructor rather than becoming a builder: there is
     * exactly one call site, and {@link DomainEvent} has no builder to inherit
     * from. Retrofitting {@code @SuperBuilder} onto the shared base class would
     * touch every event type in the platform to tidy one signature.
     */
    public EventSearchIndexedEvent(String traceId, String sagaId,
                                    String eventId, String name, String status,
                                    Instant salesOpenAt, Instant salesCloseAt, Instant eventDate,
                                    String primaryArtist, String venueName, String venueCity,
                                    String shortDescription, String fullDescription,
                                    String category, String genre,
                                    java.math.BigDecimal priceMin,
                                    java.math.BigDecimal priceMax,
                                    Integer ticketCount) {
        super(traceId, sagaId);
        this.priceMin    = priceMin;
        this.priceMax    = priceMax;
        this.ticketCount = ticketCount;
        this.eventId          = eventId;
        this.name             = name;
        this.status           = status;
        this.salesOpenAt      = salesOpenAt;
        this.salesCloseAt     = salesCloseAt;
        this.eventDate        = eventDate;
        this.primaryArtist    = primaryArtist;
        this.venueName        = venueName;
        this.venueCity        = venueCity;
        this.shortDescription = shortDescription;
        this.fullDescription  = fullDescription;
        this.category         = category;
        this.genre            = genre;
    }
}
