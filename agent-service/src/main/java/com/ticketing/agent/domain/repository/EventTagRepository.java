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
    @Modifying
    @Query("DELETE FROM EventTag t WHERE t.eventId = :eventId AND t.source = 'llm'")
    int deleteLlmTags(@Param("eventId") String eventId);
}
