package com.ticketing.agent.ingest;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.model.EventTag;
import com.ticketing.agent.domain.repository.EventTagRepository;
import com.ticketing.agent.vector.TagMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records that a facet's vector landed near a tag, without deciding anything.
 *
 * <p>Shared by ingestion and by the facet backfill so the two cannot drift.
 * Both arrive at the same place — a facet with a vector and a nearest tag on
 * the same dim — and the row they write has to mean the same thing either way.
 *
 * <p>No threshold is applied. The measured score distribution is unimodal
 * between 0.35 and 0.75 with no gap, so any cut-off chosen now would be a guess
 * frozen into every row. The score is stored and the decision is left to review.
 */
@Component
@RequiredArgsConstructor
public class TagSuggester {

    private final EventTagRepository eventTagRepository;
    private final AgentProperties    properties;

    /**
     * Several facets on one dim can point at the same tag — an event with three
     * format facets may suggest {@code live-music} three times. The strongest
     * of them is the honest score for the pair, so a weaker later facet must
     * not overwrite it.
     *
     * <p>An approved row is never touched. Once a reviewer has ruled on the
     * pair, re-running ingestion must not quietly reopen it.
     */
    public void suggest(String eventId, TagMatcher.Candidate c) {
        var existing = eventTagRepository.findById(new EventTag.Key(eventId, c.tagId()));
        if (existing.isPresent()) {
            EventTag row = existing.get();
            // A pair with a verdict is settled. Re-scoring an approved row
            // would be harmless but pointless; re-scoring a rejected one would
            // quietly restart an argument a reviewer already ended.
            if (row.getApprovedAt() == null && row.getRejectedAt() == null
                    && (row.getConfidence() == null || row.getConfidence() < c.score())) {
                row.setConfidence((float) c.score());
                eventTagRepository.save(row);
            }
            return;
        }
        eventTagRepository.save(EventTag.builder()
                .eventId(eventId)
                .tagId(c.tagId())
                .source("llm")
                .confidence((float) c.score())
                .approvedAt(accepted(c) ? java.time.Instant.now() : null)
                .build());
    }

    private boolean accepted(TagMatcher.Candidate c) {
        var v = properties.getValidation();
        return v.isAutoApproveOnAllGatesPass() && c.score() >= v.getTagMatchThreshold();
    }
}
