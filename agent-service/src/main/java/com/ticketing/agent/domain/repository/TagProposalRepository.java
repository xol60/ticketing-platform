package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.TagProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TagProposalRepository extends JpaRepository<TagProposal, Long> {

    /**
     * Records one sighting of an unsnappable label, upserting on the label
     * itself so the count accumulates across events.
     *
     * <p>Native because {@code ON CONFLICT} has no JPA equivalent, and the
     * alternative — read, branch, write — would race two ingest threads into a
     * unique-violation on {@code raw_label}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO tag_proposal (raw_label, seen_count, last_event_id,
                                      nearest_slug, nearest_score,
                                      first_seen_at, last_seen_at)
            VALUES (:rawLabel, 1, :eventId, :nearestSlug, :nearestScore, NOW(), NOW())
            ON CONFLICT (raw_label) DO UPDATE
               SET seen_count    = tag_proposal.seen_count + 1,
                   last_event_id = EXCLUDED.last_event_id,
                   nearest_slug  = EXCLUDED.nearest_slug,
                   nearest_score = EXCLUDED.nearest_score,
                   last_seen_at  = NOW()
            """, nativeQuery = true)
    void record(@Param("rawLabel") String rawLabel,
                @Param("eventId") String eventId,
                @Param("nearestSlug") String nearestSlug,
                @Param("nearestScore") Float nearestScore);

    /** Promotion candidates: seen often, and not close to anything that exists. */
    List<TagProposal> findBySeenCountGreaterThanEqualOrderBySeenCountDesc(int minSeen);
}
