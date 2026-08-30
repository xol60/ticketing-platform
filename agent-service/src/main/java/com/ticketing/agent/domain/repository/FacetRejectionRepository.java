package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.FacetRejection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FacetRejectionRepository extends JpaRepository<FacetRejection, Long> {

    List<FacetRejection> findByEventId(String eventId);

    /**
     * Rejections grouped by reason — the extractor's failure profile.
     *
     * <p>Read this after every prompt or model change. The shape of the
     * distribution says what to fix, and the reasons point in different
     * directions:
     *
     * <ul>
     *   <li>{@code SPAN_NOT_IN_SOURCE} dominating → the model is writing from
     *       its own priors instead of the description. A prompt problem.</li>
     *   <li>{@code LOW_SPAN_OVERLAP} dominating → it is reading the source but
     *       drifting away from what it quoted. Usually too much freedom in how
     *       the facet may be phrased.</li>
     *   <li>{@code CONTRADICTS_EVENT} dominating → it is asserting things the
     *       database already disproves, which means the prompt is not giving
     *       it the structured facts it should be constrained by.</li>
     * </ul>
     *
     * @return rows of {@code [reason, count]}, most frequent first
     */
    @Query(value = """
            SELECT reason, COUNT(*) AS n
              FROM facet_rejection
             GROUP BY reason
             ORDER BY n DESC
            """, nativeQuery = true)
    List<Object[]> countByReason();
}
