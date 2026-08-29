package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TagRepository extends JpaRepository<TagEntity, Integer> {

    Optional<TagEntity> findBySlug(String slug);

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
