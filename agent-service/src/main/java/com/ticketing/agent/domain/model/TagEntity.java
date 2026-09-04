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
     * tag is reachable by exclusion only — {@code headliner} and
     * {@code late-night} describe an artist's fame and a start time, neither of
     * which is a dimension of the experience.
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
     * {@code description} while the vector comes from the tag's own prose,
     * {@code knn} once enough approved events carry the tag for their centroid
     * to describe it better than the prose does.
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
