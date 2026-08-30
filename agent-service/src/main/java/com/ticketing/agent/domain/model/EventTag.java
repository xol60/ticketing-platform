package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;

/**
 * Join row: this event carries this tag.
 *
 * <p>{@link #source} keeps a human decision from being clobbered by the next
 * ingest of the same event — re-ingest rewrites {@code llm} rows and leaves
 * {@code human} rows alone.
 *
 * <p>{@link #approvedAt} gates the exclude filter. A query saying "not too
 * crowded" resolves to {@code NOT EXISTS (… tag_id = large-scale AND
 * approved_at IS NOT NULL)} — hiding an event on the strength of an unreviewed
 * machine guess would silently shrink the catalogue for a reason nobody
 * checked.
 */
@Entity
@Table(name = "event_tag")
@IdClass(EventTag.Key.class)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventTag {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private Integer tagId;

    /** {@code llm} or {@code human}. */
    @Column(nullable = false)
    @Builder.Default
    private String source = "llm";

    private Float confidence;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /**
     * When a reviewer ruled this pair wrong.
     *
     * <p>Mutually exclusive with {@link #approvedAt} — a pair has at most one
     * verdict, enforced by a check constraint rather than by convention.
     *
     * <p>This is why a rejection is a column and not a deletion. The matcher is
     * deterministic, so re-ingesting an event whose rejected rows were deleted
     * regenerates them unchanged; the only way for "no" to mean anything past
     * the next ingest is to write it down.
     */
    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String  eventId;
        private Integer tagId;
    }
}
