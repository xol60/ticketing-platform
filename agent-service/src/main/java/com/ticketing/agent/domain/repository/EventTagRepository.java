package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.EventTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventTagRepository extends JpaRepository<EventTag, EventTag.Key> {

    List<EventTag> findByEventId(String eventId);

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
