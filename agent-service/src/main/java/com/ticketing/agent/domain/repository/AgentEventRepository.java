package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.AgentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentEventRepository extends JpaRepository<AgentEvent, String> {

    /**
     * The review queue, oldest first — a backlog here is the failure mode that
     * makes the whole catalogue look empty, because nothing becomes searchable
     * until someone works through it.
     */
    Page<AgentEvent> findBySearchableFalseOrderByIngestedAtAsc(Pageable pageable);

    /**
     * The number that decides whether vibe matching can exist at all.
     *
     * <p>An event whose source description says only "Live music. 8pm. $40."
     * yields no atmosphere and no format — no LLM can distil what was never
     * written. Such events are invisible to every vibe query and reachable
     * only through the hard filter.
     *
     * <p>If this ratio sits below roughly 60%, the problem is upstream in event
     * authoring, and no amount of prompt or ranking work will fix it. Measure
     * it before trusting anything else in this service.
     */
    @Query("""
            SELECT COUNT(e) FROM AgentEvent e
            WHERE (SELECT COUNT(f) FROM EventFacet f WHERE f.eventId = e.id) >= :minFacets
            """)
    long countWithAtLeastFacets(int minFacets);
}
