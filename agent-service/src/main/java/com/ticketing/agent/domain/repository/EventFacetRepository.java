package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.EventFacet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventFacetRepository extends JpaRepository<EventFacet, Long> {

    List<EventFacet> findByEventId(String eventId);

    /**
     * Clears machine-generated facets before a re-ingest writes fresh ones.
     * Human rows survive: a reviewer's correction must not be undone by the
     * next metadata edit upstream.
     */
    @Modifying
    @Query("DELETE FROM EventFacet f WHERE f.eventId = :eventId AND f.source = 'llm'")
    int deleteLlmFacets(@Param("eventId") String eventId);

    /**
     * Writes the vector for one facet.
     *
     * <p>Native, because the parameter is a pgvector literal and JPA has no
     * type for it. The value is bound as text and cast in SQL, which keeps the
     * JVM free of any vector representation — see {@link EventFacet} for why
     * that is the design rather than a shortcut.
     *
     * @param vectorLiteral pgvector text form, e.g. {@code [0.013,-0.28,...]}
     */
    @Modifying
    @Query(value = """
            UPDATE event_facet
               SET embedding = CAST(:vectorLiteral AS vector),
                   model_version = :modelVersion
             WHERE id = :id
            """, nativeQuery = true)
    int writeEmbedding(@Param("id") Long id,
                       @Param("vectorLiteral") String vectorLiteral,
                       @Param("modelVersion") String modelVersion);

    /**
     * Mean cosine distance from a candidate value to everything already
     * approved on the same dim — the dim-validation check.
     *
     * <p>Catches the common extraction error where atmosphere content lands in
     * the format slot. Returns null when the dim has no approved rows yet, in
     * which case validation cannot run and the facet goes to review by
     * default.
     */
    @Query(value = """
            SELECT AVG(1 - (f.embedding <=> CAST(:vectorLiteral AS vector)))
              FROM event_facet f
             WHERE f.dim = :dim
               AND f.embedding IS NOT NULL
               AND f.approved_at IS NOT NULL
            """, nativeQuery = true)
    Double meanSimilarityWithinDim(@Param("dim") String dim,
                                   @Param("vectorLiteral") String vectorLiteral);
}
