package com.ticketing.search.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.search.dto.response.AutocompleteSuggestion;
import com.ticketing.search.dto.response.EventSearchPage;
import com.ticketing.search.service.EventSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public REST endpoints for event search.
 *
 * <p>Both endpoints are intentionally cheap to call — no auth required, no
 * write-side dependencies, no cross-service fan-out. They read straight from
 * ES. Postgres / ticket-service stay completely out of the request path.
 *
 * <p>If the user clicks a result, the UI then fetches canonical event detail
 * from {@code /api/tickets/events/{id}} and the ticket list from
 * {@code /api/tickets/events/{id}/tickets} — both served by ticket-service
 * against the source-of-truth Postgres.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final EventSearchService searchService;

    /**
     * Multi-field full-text search with optional faceted filtering.
     *
     * @param q        the user-typed query (required, non-blank)
     * @param category optional CONCERT | SPORTS | THEATER | CONFERENCE filter
     * @param genre    optional genre filter
     * @param from     zero-based start offset (default 0)
     * @param size     page size (default 20, max 100 enforced server-side)
     */
    @GetMapping("/events")
    public ApiResponse<EventSearchPage> search(
            @RequestParam("q")                       String query,
            @RequestParam(required = false)          String category,
            @RequestParam(required = false)          String genre,
            @RequestParam(defaultValue = "0")        int    from,
            @RequestParam(defaultValue = "20")       int    size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (query == null || query.isBlank()) {
            return ApiResponse.error("INVALID_QUERY", "query 'q' is required", traceId);
        }

        return ApiResponse.ok(
                searchService.search(query, category, genre, from, size),
                traceId);
    }

    /**
     * Autocomplete-as-you-type. Designed to be called on every keystroke from
     * the second character on. Returns at most {@code size} (max 10)
     * suggestions ranked by relevance, restricted to OPEN events.
     */
    @GetMapping("/events/suggest")
    public ApiResponse<List<AutocompleteSuggestion>> suggest(
            @RequestParam("q")                 String query,
            @RequestParam(defaultValue = "5")  int    size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (query == null || query.length() < 2) {
            // Convention: don't error on too-short input — return empty so the
            // UI can stay silent until the user has typed enough.
            return ApiResponse.ok(List.of(), traceId);
        }

        return ApiResponse.ok(searchService.suggest(query, size), traceId);
    }
}
