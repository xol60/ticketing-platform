package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.FacetRejection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Reinstates every overridden rejection as a real facet.
     *
     * <p>Written as one statement so the reviewer's decision and its effect
     * cannot drift apart. The promoted row keeps {@code source = 'llm'} and its
     * original span: a reviewer overturning the overlap gate is saying the
     * model's facet was fair after all, not claiming authorship of it. Marking
     * it {@code human} would exempt it from the span requirement and lose the
     * evidence that made the override defensible.
     *
     * <p>{@code approved_at} is set because the override <em>is</em> the review
     * — routing it back to a queue nobody reads is how it got stuck the first
     * time. Embedding and candidate lists follow from the ordinary backfills,
     * which look for a null embedding and a missing candidate list.
     *
     * <p>Idempotent by {@code NOT EXISTS}, so a second boot promotes nothing.
     *
     * @return rows promoted
     */
    @Modifying
    @Query(value = """
            INSERT INTO event_facet (event_id, dim, value, source_span, source,
                                     model_version, approved_at, created_at)
            SELECT r.event_id, r.dim, r.value, r.source_span, 'llm',
                   r.model_version, now(), now()
              FROM facet_rejection r
             WHERE r.overridden_at IS NOT NULL
               AND NOT EXISTS (
                     SELECT 1 FROM event_facet f
                      WHERE f.event_id = r.event_id
                        AND f.dim = r.dim
                        AND f.value = r.value)
            """, nativeQuery = true)
    int promoteOverridden();
}
