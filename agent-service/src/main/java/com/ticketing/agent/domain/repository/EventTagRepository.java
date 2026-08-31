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
}
