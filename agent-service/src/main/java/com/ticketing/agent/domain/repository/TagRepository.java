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

    /**
     * The same list, but only tags that beat every tag on every other dim.
     *
     * <p>The ingest-side answer to "does any tag cover this facet", and a
     * comparison rather than a threshold — which is the only form that works
     * here. A cosine floor cannot be set, because the score depends on how much
     * text the facet carries as much as on what it means: measured on this
     * corpus, {@code technical and focused} scored 0.451 against a tag named
     * Focused and Technical while {@code spectators} scored 0.456 against a tag
     * about investors. No number separates those, and a floor of 0.47 cut four
     * correct matches to catch one wrong one.
     *
     * <p>Both sides of this comparison embed the same facet, so length cancels.
     * The question becomes "is this facet more like its own dim's vocabulary
     * than like any other dim's", which is decidable and needs no calibration.
     * Measured against the same cases: it keeps {@code 72,000 attendees} and
     * {@code wembley stadium headline performance}, and cuts {@code spectators}
     * and {@code national television audiences} — the latter being the single
     * largest error class in the last review, where seven broadcast facets were
     * assigned an all-ages family tag.
     *
     * <p>Returns nothing when the facet belongs to a dim it is not on. That is
     * the signal the reviewer needs: no tag here covers this, write one — or
     * accept that the facet was misfiled and the answer is not on this dim at
     * all.
     */
    @Query(value = """
            SELECT t.id, t.slug, (1 - (t.embedding <=> CAST(:vectorLiteral AS vector))) AS sim
              FROM tag t
             WHERE t.dim = :dim
               AND t.embedding IS NOT NULL
               AND (1 - (t.embedding <=> CAST(:vectorLiteral AS vector))) >
                   COALESCE((SELECT max(1 - (o.embedding <=> CAST(:vectorLiteral AS vector)))
                               FROM tag o
                              WHERE o.dim IS NOT NULL AND o.dim <> :dim
                                AND o.embedding IS NOT NULL), 0)
             ORDER BY t.embedding <=> CAST(:vectorLiteral AS vector)
             LIMIT :topN
            """, nativeQuery = true)
    List<Object[]> findCoveringInDim(@Param("dim") String dim,
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



    /**
     * The closest any two existing tags on a dim sit to each other.
     *
     * <p>The baseline a candidate tag is judged against, and the reason no
     * constant appears anywhere in duplicate detection. Absolute cosine says
     * nothing on its own: measured on the live vocabulary,
     * {@code focused-and-technical} and {@code formal-ceremonial} score 0.721
     * against each other, {@code team-sport-fixture} and {@code combat-sport}
     * 0.716, {@code stadium-crowd} and {@code broadcast-audience} 0.689 — pairs
     * nobody would confuse. A fixed threshold either sits below those and flags
     * every tag, or above them and flags none.
     *
     * <p>What is decidable is a comparison. If a candidate is closer to some
     * existing tag than any two existing tags are to each other, it is inside
     * a distance the vocabulary has never had to tell apart. That bar moves on
     * its own as the vocabulary grows, and it needs no calibration when the
     * embedding model is replaced.
     *
     * <p>Returns null below three tags, not below two. One pair is a single
     * sample, not a distribution: it lands wherever those two definitions
     * happen to sit, and a third tag that is merely on-topic clears it.
     * Measured while building this vocabulary — with {@code live-music-concert}
     * and {@code staged-drama} alone on {@code format}, the bar came out low
     * enough to reject {@code team-sport-fixture}, {@code motorsport-race} and
     * {@code combat-sport} in turn, none of which resembles either. Three tags
     * is three pairs, which is where a maximum starts describing the dim
     * rather than one accident of wording.
     *
     * @return null when the dim holds fewer than three embedded tags, where
     *         the question has no answer yet
     */
    @Query(value = """
            SELECT CASE WHEN count(*) >= 3 THEN max(1 - (a.embedding <=> b.embedding)) END
              FROM tag a JOIN tag b ON a.id < b.id
             WHERE a.dim = :dim AND b.dim = :dim
               AND a.embedding IS NOT NULL AND b.embedding IS NOT NULL
            """, nativeQuery = true)
    Double closestExistingPair(@Param("dim") String dim);

    /**
     * The two nearest tags to a phrase, across every dim.
     *
     * <p>Resolves what a person ruled out. Two rows rather than one, because
     * the decision is not "how close is the nearest tag" but "does one tag
     * stand out" — and only the runner-up can answer that. Measured on the
     * phrases this corpus produces:
     *
     * <pre>
     *   gap    phrase             nearest              verdict
     *   0.073  a conference       conference-keynote   right to exclude
     *   0.063  football           team-sport-fixture   right to exclude
     *   0.051  too crowded        stadium-crowd        right to exclude
     *   0.037  sports             team-sport-fixture   right to exclude
     *   0.013  a musical          staged-drama         WRONG — kills ballet too
     *   0.010  electronic music   live-music-concert   WRONG — kills the request
     * </pre>
     *
     * <p>The two catastrophic cases have the smallest gaps, and they share a
     * cause: the vocabulary has no tag meaning "musical as opposed to ballet"
     * or "electronic as opposed to live", so several tags sit equally near and
     * none of them is what was named. Absolute similarity cannot see this —
     * {@code a musical} scores 0.561, higher than {@code sports} at 0.558.
     *
     * <p>Not restricted by dim: a person rules out a thing, not a dimension.
     *
     * @return up to two rows of {@code [slug, similarity]}, nearest first
     */
    @Query(value = """
            SELECT t.slug, (1 - (t.embedding <=> CAST(:vectorLiteral AS vector))) AS sim
              FROM tag t
             WHERE t.embedding IS NOT NULL
             ORDER BY t.embedding <=> CAST(:vectorLiteral AS vector)
             LIMIT 2
            """, nativeQuery = true)
    List<Object[]> nearestTwo(@Param("vectorLiteral") String vectorLiteral);
}
