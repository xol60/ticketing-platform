package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<TagEntity, Integer> {

    Optional<TagEntity> findBySlug(String slug);

    /**
     * Nearest tag to a facet, restricted to tags on the same dim.
     *
     * <p>The dim restriction is not an optimisation. Measured on the running
     * model, two phrases on <em>different</em> dims score 0.568 while two
     * unrelated phrases on the same dim score 0.452 — cross-dim comparison
     * flattens the space until everything looks equally relevant. Without the
     * restriction, "a small room, close to the performer" matched
     * {@code live-music} instead of {@code intimate}.
     *
     * <p>Tags with a null dim are excluded by the join condition. They are
     * reachable through {@code NOT EXISTS} only, and letting them compete here
     * would put an artist's fame in contention with a phrase about room size.
     *
     * @return rows of {@code [tag_id, slug, similarity]}, strongest first
     */
    @Query(value = """
            SELECT t.id, t.slug, (1 - (t.embedding <=> CAST(:vectorLiteral AS vector))) AS sim
              FROM tag t
             WHERE t.dim = :dim
               AND t.embedding IS NOT NULL
             ORDER BY t.embedding <=> CAST(:vectorLiteral AS vector)
             LIMIT :topN
            """, nativeQuery = true)
    List<Object[]> findNearestInDim(@Param("dim") String dim,
                                    @Param("vectorLiteral") String vectorLiteral,
                                    @Param("topN") int topN);


    @Modifying
    @Query(value = """
            UPDATE tag
               SET embedding = CAST(:vectorLiteral AS vector),
                   vector_source = :source,
                   model_version = :modelVersion
             WHERE id = :id
            """, nativeQuery = true)
    int writeEmbedding(@Param("id") Integer id,
                       @Param("vectorLiteral") String vectorLiteral,
                       @Param("source") String source,
                       @Param("modelVersion") String modelVersion);

    /** Tags a reviewer added, which TagSynchronizer must leave alone. */
    List<TagEntity> findBySource(String source);

    /**
     * Nearest tag to a free-form label, by cosine — the tag-snapping lookup.
     *
     * <p>Returns {@code (slug, similarity)} for the single closest tag. The
     * caller compares the similarity against the snap threshold: above it, the
     * label becomes that tag; below it, the label becomes a proposal and no
     * tag is created. Fifteen rows, so a sequential scan is the right plan and
     * no index is wanted here.
     */
    @Query(value = """
            SELECT t.slug, (1 - (t.embedding <=> CAST(:vectorLiteral AS vector))) AS similarity
              FROM tag t
             WHERE t.embedding IS NOT NULL
             ORDER BY t.embedding <=> CAST(:vectorLiteral AS vector)
             LIMIT 1
            """, nativeQuery = true)
    Object[] findNearest(@Param("vectorLiteral") String vectorLiteral);
}
