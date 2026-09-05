package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Database row for one of the 15 tags.
 *
 * <p>Named {@code TagEntity} rather than {@code Tag} because
 * {@code com.ticketing.common.agent.Taxonomy.Tag} was once the definition and this
 * merely its persisted shadow. Java owns slug, name, description and kind;
 * was its shadow, pushed here on every startup. That is reversed: this table is
 * the definition, written only by a reviewer through the curation API. It exists
 * so a tag can be joined in SQL and can carry a vector — never so someone can
 * add a sixteenth tag with an INSERT.
 *
 * <p>The embedding column is unmapped for the same reason as on
 * {@link EventFacet}: vectors are written and compared inside Postgres.
 */
@Entity
@Table(name = "tag")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    /**
     * Which of the eight dimensions this tag answers, or null.
     *
     * <p>A facet is only compared against tags on its own dim. Null means the
     * tag is reachable by exclusion only. The seed vocabulary had two — one
     * about an artist's fame, one about a start time — and neither was a
     * dimension of the experience, which is why forcing them onto one would
     * have put them in competition with facets they do not describe. The
     * vocabulary holds none today.
     */
    private String dim;

    /** Concrete phrasings, embedded alongside name and description. */
    @Column(columnDefinition = "TEXT")
    private String examples;

    /**
     * Always {@code human} — a CHECK constraint allows nothing else since V12.
     *
     * <p>The column once separated {@code taxonomy} rows, which a startup bean
     * rewrote from Java on every boot, from {@code human} rows it had to leave
     * alone; without it a reviewer-added tag was silently reverted on the next
     * restart and the vocabulary could only shrink back to its starting set.
     * There is no seeder now, so the distinction has one side. It is kept as a
     * stated invariant rather than dropped, because "every tag in this table
     * was written by a person" is the property the whole curation flow rests
     * on, and a CHECK says it where a comment would not.
     */
    @Column(nullable = false)
    @Builder.Default
    private String source = "human";

    /**
     * Always {@code description} — the vector is built from the tag's own
     * prose, and V13's CHECK allows nothing else.
     *
     * <p>V1 reserved a second value, {@code knn}, for the centroid of approved
     * events carrying the tag. It was never implemented and was removed rather
     * than left waiting: a facet's vector is built from value plus span, and a
     * span is raw source text, so averaging them would fold every description
     * author's phrasing into the definition until the tag no longer meant what
     * its reviewer wrote. A definition that moves on its own cannot be
     * reviewed.
     *
     * <p>Null means the vector is missing or stale and the startup backfill
     * will rebuild it — which is how an edited definition takes effect.
     */
    @Column(name = "vector_source")
    private String vectorSource;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
