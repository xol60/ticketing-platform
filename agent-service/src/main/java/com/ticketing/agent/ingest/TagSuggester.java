package com.ticketing.agent.ingest;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.model.EventTag;
import com.ticketing.agent.domain.model.FacetTagCandidate;
import com.ticketing.agent.domain.repository.EventTagRepository;
import com.ticketing.agent.domain.repository.FacetTagCandidateRepository;
import com.ticketing.agent.vector.TagMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Records which tags a facet landed near, and provisionally accepts the nearest.
 *
 * <p>Shared by ingestion and by the facet backfill so the two cannot drift.
 * Both arrive at the same place — a facet with a vector — and what they write
 * has to mean the same thing either way.
 *
 * <h3>Two records, two lifetimes</h3>
 * The candidate list belongs to the facet and dies with it, because re-ingest
 * deletes and recreates facets. A verdict belongs to the (event, tag) pair and
 * outlives every ingest. Conflating the two is how the first version lost its
 * review: rejections stored per facet reappeared as fresh suggestions the next
 * time the event was read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagSuggester {

    /**
     * How many tags per facet the review gets to see.
     *
     * <p>Three, from the measured data rather than taste. Where the nearest tag
     * was wrong, the right one was at rank two — sometimes by 0.001, once by
     * nothing at all: "free practice sessions" scored 0.495 against both
     * workshop and sports, and a Formula 1 race was tagged workshop because row
     * order broke the tie. Beyond rank three the scores were uniformly below
     * anything worth showing.
     */
    public static final int CANDIDATES_PER_FACET = 3;

    private final EventTagRepository          eventTagRepository;
    private final FacetTagCandidateRepository candidateRepository;
    private final AgentProperties             properties;

    /**
     * @param candidates nearest first, as returned by
     *                   {@link TagMatcher#candidatesFor}
     */
    public void record(String eventId, Long facetId, List<TagMatcher.Candidate> candidates) {
        candidateRepository.deleteForFacet(facetId);
        if (candidates.isEmpty()) return;

        short rank = 1;
        for (TagMatcher.Candidate c : candidates) {
            candidateRepository.save(FacetTagCandidate.builder()
                    .facetId(facetId).tagId(c.tagId())
                    .score((float) c.score()).rank(rank++)
                    .build());
        }

        // Only the nearest is ever proposed as an assignment. A rank-two tag is
        // shown to the reviewer as an alternative, not asserted — recording it
        // as an assignment too would mean claiming an event is both a workshop
        // and a sports fixture because one sentence read ambiguously.
        TagMatcher.Candidate best = candidates.get(0);
        upsertVerdict(eventId, best);
    }

    private void upsertVerdict(String eventId, TagMatcher.Candidate c) {
        var existing = eventTagRepository.findById(new EventTag.Key(eventId, c.tagId()));
        if (existing.isPresent()) {
            EventTag row = existing.get();
            // A pair with a verdict is settled. Re-scoring an approved row
            // would be harmless but pointless; re-scoring a rejected one would
            // quietly restart an argument a reviewer already ended.
            if (row.getApprovedAt() == null && row.getRejectedAt() == null
                    && (row.getConfidence() == null || row.getConfidence() < c.score())) {
                row.setConfidence((float) c.score());
                if (accepted(c)) row.setApprovedAt(Instant.now());
                eventTagRepository.save(row);
            }
            return;
        }
        eventTagRepository.save(EventTag.builder()
                .eventId(eventId)
                .tagId(c.tagId())
                .source("llm")
                .confidence((float) c.score())
                .approvedAt(accepted(c) ? Instant.now() : null)
                .build());
    }

    private boolean accepted(TagMatcher.Candidate c) {
        var v = properties.getValidation();
        return v.isAutoApproveOnAllGatesPass() && c.score() >= v.getTagMatchThreshold();
    }
}
