package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A label the extractor emitted that snapped to no existing tag.
 *
 * <p>The point of this table is that it is <em>not</em> the tag table. When the
 * model produces "jazz night" or "acoustic", the cheap move is to create the
 * tag and move on — and then the vocabulary grows at ingest speed, every filter
 * built on it gets fuzzier, and nobody ever notices because nothing failed.
 * Landing here instead means the tag set only ever grows when a person decides
 * it should.
 *
 * <p>{@link #nearestSlug} and {@link #nearestScore} record what it almost
 * matched, which is what makes the periodic review quick: a label seen fifty
 * times whose nearest neighbour scores 0.45 is a real gap in the taxonomy; one
 * seen twice at 0.80 is a paraphrase that should have snapped and probably
 * means the threshold is a shade too high.
 */
@Entity
@Table(name = "tag_proposal")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The label as emitted, normalised only for case and whitespace. */
    @Column(name = "raw_label", nullable = false, unique = true)
    private String rawLabel;

    /** How often this exact label has come up. The signal for promotion. */
    @Column(name = "seen_count", nullable = false)
    @Builder.Default
    private Integer seenCount = 1;

    @Column(name = "last_event_id")
    private String lastEventId;

    @Column(name = "nearest_slug")
    private String nearestSlug;

    @Column(name = "nearest_score")
    private Float nearestScore;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.firstSeenAt == null) this.firstSeenAt = now;
        if (this.lastSeenAt  == null) this.lastSeenAt  = now;
    }
}
