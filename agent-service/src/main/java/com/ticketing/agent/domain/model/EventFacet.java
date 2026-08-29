package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One distilled statement about an event, under a constrained {@code dim}.
 *
 * <h3>The embedding column is not mapped here, on purpose</h3>
 * {@code event_facet.embedding vector(1024)} exists in the schema but has no
 * field on this entity. The JVM never needs to hold a vector: embeddings are
 * written by native statements with an explicit {@code CAST(? AS vector)} and
 * compared entirely inside Postgres. Mapping the column would mean pulling in
 * a pgvector JDBC type and shipping 1024 floats per row into Java on every
 * read, to do nothing with them.
 *
 * <p>Hibernate's {@code ddl-auto: validate} checks that mapped fields exist in
 * the schema, not that every column is mapped, so the unmapped column is fine.
 *
 * <h3>Several facets per dim, or none</h3>
 * There is no unique constraint on {@code (event_id, dim)}. An event that says
 * nothing about its atmosphere carries no atmosphere facet, and that absence
 * is information — it simply never scores on that dim. Forcing one row per dim
 * would push the extractor into inventing values to fill the shape, and once
 * embedded, an invented facet is indistinguishable from a real one.
 */
@Entity
@Table(name = "event_facet")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventFacet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    /** One of the eight names in {@code Taxonomy.DIM_NAMES}. Unknown dims are dropped at ingest. */
    @Column(nullable = false)
    private String dim;

    /** The distilled statement itself. This is what gets embedded, for the three embedded dims. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    /** Embedding model that produced the vector. Null while the row has no vector. */
    @Column(name = "model_version")
    private String modelVersion;

    /** {@code llm} or {@code human} — a reviewer's edit must survive the next ingest. */
    @Column(nullable = false)
    @Builder.Default
    private String source = "llm";

    /** Null until reviewed. Only approved facets take part in matching. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }
}
