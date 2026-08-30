package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Database row for one of the 15 tags.
 *
 * <p>Named {@code TagEntity} rather than {@code Tag} because
 * {@code com.ticketing.common.agent.Taxonomy.Tag} is the definition and this is
 * merely its persisted shadow. Java owns slug, name, description and kind;
 * {@code TagSynchronizer} pushes them here on every startup. This table exists
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
     * {@code taxonomy} for the tags defined in Java, {@code human} for tags a
     * reviewer added.
     *
     * <p>TagSynchronizer rewrites every taxonomy row on each startup. Without
     * this column a reviewer-added tag would be silently reverted on the next
     * restart, so the vocabulary could only ever shrink back to its starting
     * set.
     */
    @Column(nullable = false)
    @Builder.Default
    private String source = "taxonomy";

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
