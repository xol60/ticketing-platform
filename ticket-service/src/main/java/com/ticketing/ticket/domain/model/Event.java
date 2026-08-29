package com.ticketing.ticket.domain.model;

import com.ticketing.common.events.EventStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "sales_open_at", nullable = false)
    private Instant salesOpenAt;

    @Column(name = "sales_close_at", nullable = false)
    private Instant salesCloseAt;

    @Column(name = "event_date", nullable = false)
    private Instant eventDate;

    /**
     * auth-service user id of the EVENT_OWNER (or ADMIN) who owns this event.
     * Set on create from the gateway-injected {@code X-User-Id}. Used to enforce
     * that an EVENT_OWNER may only manage their own events (ADMIN bypasses).
     * Cross-DB reference, so no FK — validated at the application tier.
     */
    @Column(name = "owner_id", length = 36)
    private String ownerId;

    // ── Searchable metadata (added for Elasticsearch event-search subsystem) ──
    // All nullable so the schema change is non-breaking for existing rows.
    @Column(name = "primary_artist", length = 255)
    private String primaryArtist;

    @Column(name = "venue_name", length = 255)
    private String venueName;

    @Column(name = "venue_city", length = 100)
    private String venueCity;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(name = "full_description", columnDefinition = "TEXT")
    private String fullDescription;

    /** CONCERT | SPORTS | THEATER | CONFERENCE | OTHER */
    @Column(name = "category", length = 50)
    private String category;

    /** POP | ROCK | EDM | JAZZ | CLASSICAL | OTHER */
    @Column(name = "genre", length = 50)
    private String genre;

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
        if (this.status == null) this.status = EventStatus.DRAFT;
    }

    public boolean isOpenForSales() {
        Instant now = Instant.now();
        return this.status == EventStatus.OPEN
                && now.isAfter(this.salesOpenAt)
                && now.isBefore(this.salesCloseAt)
                && now.isBefore(this.eventDate);
    }
}
