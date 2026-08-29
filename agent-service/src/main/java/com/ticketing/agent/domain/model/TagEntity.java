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

    /** CATEGORY or ATTRIBUTE. Category answers "what is it", attribute answers "what is it like". */
    @Column(nullable = false)
    private String kind;

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
