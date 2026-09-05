package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.EventFacet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EventFacetRepository extends JpaRepository<EventFacet, Long> {

    List<EventFacet> findByEventId(String eventId);

    /**
     * Best match for this vector on <em>every</em> dim that has approved,
     * embedded facets — the input to a discriminative dim check.
     *
     * <p>Replaces asking "is this similar enough to its own dim", which was the
     * wrong question. That test used a mean against the dim's whole approved
     * set, so a correctly-filed format facet about football scored low simply
     * because the other approved format facets were concerts and conferences.
     * Legitimate diversity inside a dim read as misfiling, and 67 of 77 format
     * facets were held back.
     *
     * <p>The question that actually matters is comparative: does this look more
     * like its own dim than like any other? That needs no threshold — only an
     * argmax — and a diverse dim no longer penalises its own members.
     *
     * @return rows of {@code [dim, best similarity]}, strongest first
     */
    @Query(value = """
            SELECT f.dim, MAX(1 - (f.embedding <=> CAST(:vectorLiteral AS vector))) AS sim
              FROM event_facet f
             WHERE f.embedding IS NOT NULL
               AND f.approved_at IS NOT NULL
             GROUP BY f.dim
             ORDER BY sim DESC
            """, nativeQuery = true)
    List<Object[]> bestSimilarityPerDim(@Param("vectorLiteral") String vectorLiteral);


    /**
     * Best match per event on one dim — the core of facet scoring.
     *
     * <p>{@code MAX} rather than {@code AVG} on purpose. An event with six
     * facets on a dim should not be penalised for the five that are irrelevant
     * to this query; they simply are not the one that matched. An event with
     * one facet should not be flattered either — it just has a single chance to
     * score. Averaging punishes richly described events for their richness.
     *
     * <p>Restricted to the same dim, because comparing an atmosphere query
     * against a format facet produces the uniform ~0.3 cosine that makes an
     * entire dataset look equally relevant.
     *
     * <p>Brute force, no ANN index. After the city and date filter the
     * candidate set is well under a thousand rows, where a sequential scan with
     * pgvector distance is sub-millisecond — and a pre-filtered HNSW traversal
     * would degrade to post-filtering and get slower, not faster.
     *
     * @return rows of {@code [event_id, similarity]}
     */
    @Query(value = """
            SELECT f.event_id, MAX(1 - (f.embedding <=> CAST(:vectorLiteral AS vector))) AS sim
              FROM event_facet f
             WHERE f.dim = :dim
               AND f.embedding IS NOT NULL
               AND f.approved_at IS NOT NULL
               AND f.event_id IN (:eventIds)
             GROUP BY f.event_id
            """, nativeQuery = true)
    List<Object[]> bestMatchPerEvent(@Param("dim") String dim,
                                     @Param("vectorLiteral") String vectorLiteral,
                                     @Param("eventIds") Collection<String> eventIds);

    /** Approved facets for rendering a result row and the compare projection. */
    List<EventFacet> findByEventIdInAndApprovedAtIsNotNull(Collection<String> eventIds);


    /**
     * How many facets on this dim the gate can actually compare against —
     * approved <em>and</em> carrying a vector.
     *
     * <p>Read by the dim gate to decide whether it has a baseline at all.
     * Below the bootstrap floor there is nothing meaningful to measure a new
     * facet against, and treating "cannot decide" as "fails" deadlocks the
     * whole dim: nothing is approved, so nothing can ever be approved.
     *
     * <p>The {@code embedding IS NOT NULL} clause is the whole point and was
     * once missing. The guard counted approved rows while the gate itself reads
     * approved rows <em>with vectors</em>, so a dim holding forty-one approved
     * but unembedded facets cleared the floor while contributing nothing to the
     * comparison — the argmax could not name that dim, and every facet on it
     * failed. The guard has to count the same rows the gate reads, or it is not
     * guarding anything.
     */
    @Query(value = """
            SELECT count(*) FROM event_facet
             WHERE dim = :dim AND approved_at IS NOT NULL AND embedding IS NOT NULL
            """, nativeQuery = true)
    long countUsableEvidence(@Param("dim") String dim);


    /** Approved facets on an embedded dim still missing their vector. */
    @Query(value = """
            SELECT * FROM event_facet
             WHERE embedding IS NULL AND dim IN (:dims)
             ORDER BY id
            """, nativeQuery = true)
    List<EventFacet> findUnembeddedOn(@Param("dims") Collection<String> dims);


    /**
     * Clears unreviewed machine facets before a re-ingest writes fresh ones.
     *
     * <p>Approved rows survive, and that clause was missing. {@code event_tag}
     * was given the same protection in V4 after rejections kept reappearing;
     * facets never got it, so any upstream description edit silently discarded
     * every approval on that event. At the time of writing that would have been
     * 309 approved facets, 264 of which nothing could restore — including the
     * thirteen the dim gate had held back and a reviewer had reinstated by
     * hand, among them the only facet Wimbledon has.
     *
     * <p>Human rows survive for the older reason: a reviewer's own correction
     * must not be undone by a metadata edit upstream.
     *
     * <p>Keeping approved rows means extraction can re-propose one that is
     * already there, so {@code persistFacets} skips a candidate that duplicates
     * an approved row rather than inserting it twice.
     */
    @Modifying
    @Query("""
            DELETE FROM EventFacet f
             WHERE f.eventId = :eventId AND f.source = 'llm'
               AND f.approvedAt IS NULL
            """)
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

    /**
     * What a candidate tag vector would win on one dim, against what already wins.
     *
     * <p>The preview behind tag creation. A new tag does not only serve the
     * facet that motivated it — it competes for every facet on its dim, and
     * that blast radius is invisible at the moment of writing a definition.
     * Measured on the seed vocabulary: a tag created to cover three facets about
     * television viewership and became rank one on eighteen, of which eleven
     * were wrong.
     *
     * @return rows of {@code [facet_id, value, score against the new vector,
     *         best score among existing tags]}
     */
    /**
     * How much of each existing tag's territory a candidate tag would take.
     *
     * <p>The question a reviewer actually needs answered before writing a tag
     * is not "does this text look like an existing definition" — measured on
     * this corpus, tags that are unmistakably distinct sit at 0.596 to 0.734
     * against each other, so text similarity has no band left to signal
     * duplication with. It is "would this tag do what one already does".
     *
     * <p>That is decidable. For each tag currently holding facets at rank one,
     * count how many of those the candidate would outscore. A tag that takes
     * most of another's facets is not a new distinction, it is a rewrite of an
     * existing one, and adding it makes the dim worse: two vectors compete for
     * the same facets, the winner varies with phrasing, and every event that
     * used to carry one tag now splits between two.
     *
     * <p>Tags holding nothing are returned too, with zero, so a reviewer can
     * still see the text similarity against a tag that has yet to win anything.
     *
     * @return rows of {@code [slug, name, facets held at rank 1, of those how
     *         many the candidate takes, cosine between the two definitions]}
     */
    @Query(value = """
            WITH held AS (
                SELECT c.facet_id, c.tag_id, c.score AS held_score
                  FROM facet_tag_candidate c
                  JOIN event_facet f ON f.id = c.facet_id
                 WHERE c.rank = 1
                   AND f.dim = :dim
                   AND f.approved_at IS NOT NULL
                   AND f.embedding IS NOT NULL
            ),
            challenger AS (
                SELECT f.id AS facet_id,
                       1 - (f.embedding <=> CAST(:vectorLiteral AS vector)) AS new_score
                  FROM event_facet f
                 WHERE f.dim = :dim
                   AND f.approved_at IS NOT NULL
                   AND f.embedding IS NOT NULL
            )
            SELECT t.slug,
                   t.name,
                   CAST(count(h.facet_id) AS integer) AS held,
                   CAST(count(*) FILTER (WHERE ch.new_score > h.held_score) AS integer) AS taken,
                   CAST(1 - (t.embedding <=> CAST(:vectorLiteral AS vector)) AS real) AS text_similarity
              FROM tag t
              LEFT JOIN held       h  ON h.tag_id    = t.id
              LEFT JOIN challenger ch ON ch.facet_id = h.facet_id
             WHERE t.dim = :dim AND t.embedding IS NOT NULL
             GROUP BY t.id
             ORDER BY taken DESC, text_similarity DESC
            """, nativeQuery = true)
    List<Object[]> overlapAgainstDim(@Param("dim") String dim,
                                     @Param("vectorLiteral") String vectorLiteral);

    @Query(value = """
            SELECT f.id, f.value,
                   CAST(1 - (f.embedding <=> CAST(:vectorLiteral AS vector)) AS real) AS new_score,
                   COALESCE((SELECT max(c.score) FROM facet_tag_candidate c
                              WHERE c.facet_id = f.id), 0) AS current_best
              FROM event_facet f
             WHERE f.dim = :dim
               AND f.embedding IS NOT NULL
               AND f.approved_at IS NOT NULL
             ORDER BY new_score DESC
            """, nativeQuery = true)
    List<Object[]> previewAgainstDim(@Param("dim") String dim,
                                     @Param("vectorLiteral") String vectorLiteral);

    /** True when this exact facet is already on the event and already approved. */
    boolean existsByEventIdAndDimAndValueAndApprovedAtIsNotNull(
            String eventId, String dim, String value);

    /**
     * Approved facets that no tag covers — the "write a definition" queue.
     *
     * <p>Empty candidate list is the signal, and it means what the cross-dim
     * comparison decided: nothing on this facet's own dim beats the best tag on
     * some other dim. Two different things land here and the reviewer is the
     * one who can tell them apart — vocabulary the dim is genuinely missing,
     * and facets the model filed on the wrong dim, where no tag on this dim
     * could ever be right.
     *
     * @return rows of {@code [facet_id, event_id, event_name, value, span,
     *         how many facets share this value]}
     */
    @Query(value = """
            SELECT f.id, f.event_id, e.name, f.value, f.source_span,
                   CAST((SELECT count(*) FROM event_facet x
                          WHERE x.dim = f.dim AND lower(x.value) = lower(f.value)
                            AND x.approved_at IS NOT NULL) AS integer) AS occurrences
              FROM event_facet f
              JOIN agent_event e ON e.id = f.event_id
             WHERE f.approved_at IS NOT NULL
               AND f.embedding IS NOT NULL
               AND (CAST(:dim AS text) IS NULL OR f.dim = CAST(:dim AS text))
               AND NOT EXISTS (SELECT 1 FROM facet_tag_candidate c WHERE c.facet_id = f.id)
             ORDER BY occurrences DESC, f.value
            """, nativeQuery = true)
    List<Object[]> uncoveredFacets(@Param("dim") String dim);
}
