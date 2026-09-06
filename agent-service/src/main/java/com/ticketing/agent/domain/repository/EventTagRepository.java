package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.EventTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EventTagRepository extends JpaRepository<EventTag, EventTag.Key> {

    List<EventTag> findByEventId(String eventId);

    /**
     * Which of these events carry which of these tags, approved.
     *
     * <p>The retrieval signal that replaced facet cosine on the dims that have
     * a vocabulary. A tag on an event is a reviewed fact — someone looked at
     * the facet, the shortlist and the scores and said yes — so membership is
     * binary and needs no threshold at query time. Cosine against a facet is a
     * fresh guess on every request, and its scale is not comparable between
     * dims: an unrelated pair on the same dim scores 0.452, which reads as
     * "somewhat relevant" and is not.
     *
     * @return rows of {@code [event_id, tag_id]}
     */
    @Query(value = """
            SELECT et.event_id, et.tag_id
              FROM event_tag et
             WHERE et.tag_id IN (:tagIds)
               AND et.event_id IN (:eventIds)
               AND et.approved_at IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> findApprovedPairs(@Param("tagIds") Collection<Integer> tagIds,
                                     @Param("eventIds") Collection<String> eventIds);

    /** Same rule as facets: re-ingest replaces machine rows, human rows stand. */
    /**
     * Clears machine suggestions before a re-ingest writes fresh ones.
     *
     * <p>Rows carrying a verdict are kept. A human {@code source} row is a
     * reviewer's own addition and was never this pipeline's to remove; an
     * approved or rejected llm row is a decision already made about a pair the
     * deterministic matcher will propose again in a moment, so deleting it
     * would silently reopen a closed question.
     */
    @Modifying
    @Query("""
            DELETE FROM EventTag t
             WHERE t.eventId = :eventId AND t.source = 'llm'
               AND t.approvedAt IS NULL AND t.rejectedAt IS NULL
            """)
    int deleteLlmTags(@Param("eventId") String eventId);

    /**
     * Proposals awaiting a verdict, with the facets that produced them.
     *
     * <p>The evidence is joined in SQL rather than fetched per row: a reviewer
     * screen for one dim is one query, not one plus a hundred.
     *
     * @param dim null for every dim
     * @return rows of {@code [event_id, event_name, tag_slug, confidence,
     *         facet values joined by " | "]}
     */
    @Query(value = """
            SELECT et.event_id, e.name, t.slug, et.confidence,
                   (SELECT string_agg(DISTINCT f.value, ' | ')
                      FROM event_facet f
                     WHERE f.event_id = et.event_id AND f.dim = t.dim
                       AND f.approved_at IS NOT NULL) AS evidence
              FROM event_tag et
              JOIN tag t         ON t.id = et.tag_id
              JOIN agent_event e ON e.id = et.event_id
             WHERE et.approved_at IS NULL AND et.rejected_at IS NULL
               AND (CAST(:dim AS text) IS NULL OR t.dim = CAST(:dim AS text))
             ORDER BY t.dim, t.slug, et.confidence DESC
            """, nativeQuery = true)
    List<Object[]> pendingForReview(@Param("dim") String dim);

    /**
     * Proposes tags on a dim by similarity to events already labelled on it.
     *
     * <h3>Why a dim needs this at all</h3>
     * Every other dim earns its tags the same way: a facet quotes a span, the
     * facet is embedded, and the vector is compared against tag definitions.
     * {@code atmosphere} cannot. Measured over 89 event descriptions, not one
     * contains a word for calm — no {@code calm}, {@code quiet},
     * {@code intimate}, {@code relaxed} or {@code unhurried} anywhere — and only
     * fourteen mention mood at all. The descriptions are reference copy:
     * composer, year, plot, revenue. They say what the event <em>is</em> and
     * never what the room <em>feels like</em>, so the grounding gate has
     * nothing to admit and the dim sat at 16 events out of 92.
     *
     * <h3>What is compared</h3>
     * An event's centroid over all its approved facet vectors, against the same
     * centroid for events a reviewer has already labelled. Not facet against
     * tag definition: that was tried and it lets one atypical facet define a
     * whole event — a Taylor Swift stadium show came back "focused and
     * technical" because it has an acoustic section. A centroid is the event's
     * whole profile, so a single unrepresentative line cannot carry it.
     *
     * <p>Validated leave-one-show-out over the labelled set: 13 of 15, and the
     * two failures were one incoherent class the reviewer then withdrew. Within
     * the four classes that survived, prediction is exact. It bridges genres —
     * Formula 1 finds Calvin Harris at 0.704, the Yankees find Formula 1 at
     * 0.633 — which is the thing a genre lookup could never do.
     *
     * <h3>Every class within the band, not just the winner</h3>
     * A single nearest class would hide the interesting case. Where two or
     * three classes sit within {@code band} of each other the event genuinely
     * reads as several things, and the answer is a person choosing — or writing
     * the tag none of them is. Proposing all of them is what puts that choice
     * in front of a reviewer instead of resolving it by argmax.
     *
     * <p>Written pending, like every other proposal in this service. Nothing
     * here approves.
     *
     * @return number of proposals written
     */
    @Modifying
    @Query(value = """
            INSERT INTO event_tag (event_id, tag_id, source, confidence)
            WITH ev AS (
                SELECT id, split_part(name, ' @ ', 1) AS show FROM agent_event),
            centroid AS (
                SELECT v.id, v.show, avg(f.embedding) AS vec
                  FROM ev v JOIN event_facet f ON f.event_id = v.id
                 WHERE f.approved_at IS NOT NULL AND f.embedding IS NOT NULL
                 GROUP BY v.id, v.show),
            anchor AS (
                SELECT DISTINCT v.show, t.id AS tag_id
                  FROM event_tag et
                  JOIN tag t ON t.id = et.tag_id
                  JOIN ev v  ON v.id = et.event_id
                 WHERE t.dim = :dim AND et.approved_at IS NOT NULL),
            anchor_vec AS (
                SELECT DISTINCT a.tag_id, c.show, c.vec
                  FROM anchor a JOIN centroid c ON c.show = a.show),
            best AS (
                SELECT c.show, av.tag_id, max(1 - (av.vec <=> c.vec)) AS sim
                  FROM centroid c CROSS JOIN anchor_vec av
                 WHERE c.show NOT IN (SELECT show FROM anchor)
                 GROUP BY c.show, av.tag_id),
            top AS (SELECT show, max(sim) AS best_sim FROM best GROUP BY show)
            SELECT c.id, b.tag_id, 'llm', CAST(b.sim AS real)
              FROM best b
              JOIN top t     ON t.show = b.show
              JOIN centroid c ON c.show = b.show
             WHERE t.best_sim >= :floor
               AND t.best_sim - b.sim <= :band
            ON CONFLICT (event_id, tag_id) DO NOTHING
            """, nativeQuery = true)
    int proposeFromAnchors(@Param("dim") String dim,
                           @Param("floor") double floor,
                           @Param("band") double band);
}
