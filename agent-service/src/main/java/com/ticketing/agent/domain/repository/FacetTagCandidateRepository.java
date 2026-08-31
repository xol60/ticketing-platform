package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.FacetTagCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FacetTagCandidateRepository
        extends JpaRepository<FacetTagCandidate, FacetTagCandidate.Key> {

    List<FacetTagCandidate> findByFacetIdOrderByRankAsc(Long facetId);

    /** Clears a facet's candidates before rewriting them, so a shrunk list cannot leave stragglers. */
    @Modifying
    @Query("DELETE FROM FacetTagCandidate c WHERE c.facetId = :facetId")
    void deleteForFacet(@Param("facetId") Long facetId);

    /**
     * Builds the candidate list for every embedded facet that has none.
     *
     * <p>Entirely inside Postgres. The alternative is reading each facet's
     * 1024 floats into the JVM, formatting them back into a pgvector literal
     * and sending them out again for comparison — which is what the ingest
     * path does only because it has just computed the vector and holds it
     * anyway. Here the vectors are already stored, so nothing needs to move.
     *
     * <p>Written with {@code CAST(x AS real)} rather than {@code x::real}:
     * Hibernate parses {@code :} in a native query as the start of a named
     * parameter, so the PostgreSQL cast shorthand becomes a syntax error at
     * runtime that no compiler sees.
     *
     * <p>Guarded by {@code NOT EXISTS} rather than by a flag, so it is a no-op
     * on every boot after the first and safe to run at any time. Adding a tag
     * invalidates existing lists on that dim — clear them for that dim and call
     * this again, which is what makes the vocabulary genuinely growable.
     *
     * @return number of candidate rows written
     */
    @Modifying
    @Query(value = """
            INSERT INTO facet_tag_candidate (facet_id, tag_id, score, rank)
            SELECT f.id, c.tag_id, c.score, c.rank
              FROM event_facet f
              CROSS JOIN LATERAL (
                   SELECT t.id AS tag_id,
                          CAST(1 - (t.embedding <=> f.embedding) AS real) AS score,
                          CAST(row_number() OVER (ORDER BY t.embedding <=> f.embedding) AS smallint) AS rank
                     FROM tag t
                    WHERE t.dim = f.dim AND t.embedding IS NOT NULL
                    ORDER BY t.embedding <=> f.embedding
                    LIMIT :topN) c
             WHERE f.embedding IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM facet_tag_candidate x WHERE x.facet_id = f.id)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int backfillMissing(@Param("topN") int topN);
}
