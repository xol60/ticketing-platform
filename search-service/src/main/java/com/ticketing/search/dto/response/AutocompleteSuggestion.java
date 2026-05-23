package com.ticketing.search.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One suggestion row returned by {@code GET /api/search/events/suggest}.
 *
 * <p>{@code eventId} lets the UI bypass another full search request — clicking
 * a suggestion can jump straight to the event-detail page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutocompleteSuggestion {
    private String eventId;
    private String text;
    private String primaryArtist;
}
