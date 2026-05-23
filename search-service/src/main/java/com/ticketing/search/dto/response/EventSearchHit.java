package com.ticketing.search.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One result row returned by {@code GET /api/search/events}.
 *
 * <p>Intentionally a light projection of {@code EventDocument}: id + the few
 * fields the search-results UI actually displays. The detail page uses
 * {@code id} to fetch canonical state from ticket-service, so we never need
 * to return every indexed field here.
 *
 * <p>{@code score} carries the raw ES BM25 relevance score so the client can
 * sort, debug, or display a "relevance: X%" hint. The list returned by the
 * server is already in descending-score order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSearchHit {
    private String  id;
    private String  name;
    private String  primaryArtist;
    private String  venueName;
    private String  venueCity;
    private String  category;
    private String  genre;
    private Instant eventDate;
    private float   score;
}
