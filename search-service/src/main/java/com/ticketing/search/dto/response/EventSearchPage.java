package com.ticketing.search.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paged envelope for {@code GET /api/search/events}.
 *
 * <p>Carries the result list plus the totals the UI needs to render
 * pagination controls without a second round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSearchPage {
    private String              query;
    private long                totalHits;
    private int                 from;
    private int                 size;
    private List<EventSearchHit> hits;
}
