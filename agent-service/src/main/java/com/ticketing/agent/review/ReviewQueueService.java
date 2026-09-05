package com.ticketing.agent.review;

import com.ticketing.agent.domain.repository.EventFacetRepository;
import com.ticketing.agent.domain.repository.EventTagRepository;
import com.ticketing.agent.dto.ReviewQueueResponse;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads the two queues a reviewer works from. Writes nothing. */
@Service
@RequiredArgsConstructor
public class ReviewQueueService {

    private final EventTagRepository   eventTagRepository;
    private final EventFacetRepository facetRepository;

    public ReviewQueueResponse forDim(String dim) {
        if (dim != null && !Taxonomy.isKnownDim(dim)) {
            throw new IllegalArgumentException("dim '" + dim + "' is not one of the eight");
        }

        List<ReviewQueueResponse.Proposal> proposals = new ArrayList<>();
        for (Object[] r : eventTagRepository.pendingForReview(dim)) {
            String joined = (String) r[4];
            proposals.add(ReviewQueueResponse.Proposal.builder()
                    .eventId((String) r[0])
                    .eventName((String) r[1])
                    .tagSlug((String) r[2])
                    .score(r[3] == null ? 0 : ((Number) r[3]).doubleValue())
                    .evidence(joined == null || joined.isBlank()
                            ? List.of() : Arrays.asList(joined.split(" \\| ")))
                    .build());
        }

        List<ReviewQueueResponse.Gap> gaps = new ArrayList<>();
        for (Object[] r : facetRepository.uncoveredFacets(dim)) {
            gaps.add(ReviewQueueResponse.Gap.builder()
                    .facetId(((Number) r[0]).longValue())
                    .eventId((String) r[1])
                    .eventName((String) r[2])
                    .value((String) r[3])
                    .sourceSpan((String) r[4])
                    .occurrences(((Number) r[5]).intValue())
                    .build());
        }

        return ReviewQueueResponse.builder()
                .dim(dim).proposals(proposals).gaps(gaps).build();
    }
}
