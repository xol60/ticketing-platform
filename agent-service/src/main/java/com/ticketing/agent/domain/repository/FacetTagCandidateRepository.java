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
                      AND (1 - (t.embedding <=> f.embedding)) >
                          COALESCE((SELECT max(1 - (o.embedding <=> f.embedding))
                                      FROM tag o
                                     WHERE o.dim IS NOT NULL AND o.dim <> f.dim
                                       AND o.embedding IS NOT NULL), 0)
                    ORDER BY t.embedding <=> f.embedding
                    LIMIT :topN) c
             WHERE f.embedding IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM facet_tag_candidate x WHERE x.facet_id = f.id)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int backfillMissing(@Param("topN") int topN);

    /** Clears every candidate list on one dim, so adding a tag can force a rebuild. */
    @Modifying
    @Query(value = """
            DELETE FROM facet_tag_candidate c
             USING event_facet f
             WHERE f.id = c.facet_id AND f.dim = :dim
            """, nativeQuery = true)
    int deleteForDim(@Param("dim") String dim);

    /**
     * Rebuilds candidate lists for one dim, whether or not they already exist.
     *
     * <p>Distinct from {@link #backfillMissing}, which is guarded by
     * {@code NOT EXISTS} and therefore cannot see that a dim's vocabulary
     * changed. Adding or re-defining a tag invalidates every list on its dim:
     * the stored scores were computed against a set of tags that no longer
     * exists, and a reviewer looking at a stale list concludes their edit did
     * nothing.
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
                      AND (1 - (t.embedding <=> f.embedding)) >
                          COALESCE((SELECT max(1 - (o.embedding <=> f.embedding))
                                      FROM tag o
                                     WHERE o.dim IS NOT NULL AND o.dim <> f.dim
                                       AND o.embedding IS NOT NULL), 0)
                    ORDER BY t.embedding <=> f.embedding
                    LIMIT :topN) c
             WHERE f.dim = :dim AND f.embedding IS NOT NULL
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int rebuildForDim(@Param("dim") String dim, @Param("topN") int topN);

    /**
     * Turns rank-one candidates on a dim into pending proposals.
     *
     * <p>The step that was missing from tag creation, and the reason the
     * feedback loop did not close. Creating a tag rebuilt every candidate list
     * on its dim but wrote no {@code event_tag} row except the single one the
     * reviewer named: the preview reported the tag would win forty facets and
     * the vocabulary then showed one carrier, with the other thirty-nine
     * appearing only after a full re-ingest of the corpus. A reviewer writing
     * a definition and seeing one event attach concludes it did not work.
     *
     * <p>Pending, never approved — approving is a person's act. Pairs that
     * already carry a verdict are left alone by the conflict clause, so this
     * cannot reopen a question a reviewer has closed.
     */
    /**
     * Drops proposals on a dim that no reviewer has answered.
     *
     * <p>Run before re-proposing, because {@link #proposeRankOneForDim} only
     * inserts. Without it a tag written early keeps every proposal it made
     * while it was alone on the dim, and the tags added afterwards cannot take
     * them back: measured on the last rebuild, {@code live-music-concert} was
     * the first tag on {@code format}, won all 201 facets, and still carried
     * proposals for Formula 1, football fixtures and a keynote long after seven
     * tags had displaced it in every candidate list. Twenty-five of its
     * eighty-five rejections were that residue.
     *
     * <p>Answered rows are untouched. A verdict is the reviewer's, and this is
     * only clearing what the matcher itself put there.
     */
    @Modifying
    @Query(value = """
            DELETE FROM event_tag et
             USING tag t
             WHERE t.id = et.tag_id AND t.dim = :dim
               AND et.source = 'llm'
               AND et.approved_at IS NULL AND et.rejected_at IS NULL
            """, nativeQuery = true)
    int clearUnansweredForDim(@Param("dim") String dim);

    @Modifying
    @Query(value = """
            INSERT INTO event_tag (event_id, tag_id, source, confidence)
            SELECT DISTINCT ON (f.event_id, c.tag_id)
                   f.event_id, c.tag_id, 'llm', c.score
              FROM facet_tag_candidate c
              JOIN event_facet f ON f.id = c.facet_id
             WHERE c.rank = 1
               AND f.dim = :dim
               AND f.approved_at IS NOT NULL
             ORDER BY f.event_id, c.tag_id, c.score DESC
            ON CONFLICT (event_id, tag_id) DO NOTHING
            """, nativeQuery = true)
    int proposeRankOneForDim(@Param("dim") String dim);
}
