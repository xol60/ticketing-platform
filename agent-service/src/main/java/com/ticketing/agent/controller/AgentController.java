package com.ticketing.agent.controller;

import com.ticketing.agent.domain.model.EventFacet;
import com.ticketing.agent.domain.repository.EventFacetRepository;
import com.ticketing.agent.dto.SearchRequest;
import com.ticketing.agent.dto.SearchResponse;
import com.ticketing.agent.search.SearchResult;
import com.ticketing.agent.search.SearchService;
import com.ticketing.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The agent's single endpoint for now: one message in, a shortlist out.
 *
 * <p>Stateless. Multi-turn state arrives with P3; until then each request is a
 * complete question, which keeps the retrieval path measurable on its own. That
 * order is deliberate — a conversational layer on top of weak retrieval is the
 * most expensive failure available here, because it looks like it works.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final SearchService        searchService;
    private final EventFacetRepository facetRepository;

    /**
     * Runs one search.
     *
     * <p>Public by design — the funnel's whole purpose is to collect a signal
     * from someone who has not committed to anything yet, and demanding a login
     * on the first message loses exactly the people it exists to serve.
     * Identity matters only at handoff, where the existing checkout flow
     * already requires it.
     */
    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@Valid @RequestBody SearchRequest request) {
        SearchResult result = searchService.search(request.getMessage(), request.getCity());

        if (!result.relaxations().isEmpty()) {
            log.info("Search relaxed {} for message: {}", result.relaxations(), request.getMessage());
        }

        return ApiResponse.ok(SearchResponse.builder()
                .hits(toHits(result))
                .totalMatched(result.totalMatched())
                .offerNarrowing(result.totalMatched() > SearchService.NARROW_THRESHOLD)
                .relaxations(result.relaxations())
                .usedVibe(result.usedVibe())
                .build());
    }

    private List<SearchResponse.Hit> toHits(SearchResult result) {
        List<String> ids = result.events().stream()
                .map(s -> s.event().getId()).toList();
        if (ids.isEmpty()) return List.of();

        Map<String, List<EventFacet>> facetsByEvent =
                facetRepository.findByEventIdInAndApprovedAtIsNotNull(ids).stream()
                        .collect(Collectors.groupingBy(EventFacet::getEventId));

        Set<String> varyingDims = dimsThatVary(facetsByEvent);

        return result.events().stream().map(s -> {
            var e = s.event();
            return SearchResponse.Hit.builder()
                    .eventId(e.getId())
                    .name(e.getName())
                    .primaryArtist(e.getPrimaryArtist())
                    .venueName(e.getVenueName())
                    .venueCity(e.getVenueCity())
                    .category(e.getCategory())
                    .startAt(e.getStartAt())
                    .priceMin(e.getPriceMin())
                    .priceMax(e.getPriceMax())
                    .differentiators(
                            facetsByEvent.getOrDefault(e.getId(), List.of()).stream()
                                    .filter(f -> varyingDims.contains(f.getDim()))
                                    .map(EventFacet::getValue)
                                    .limit(3).toList())
                    .score(s.score())
                    .build();
        }).toList();
    }

    /**
     * The dims worth showing: those where the results actually differ.
     *
     * <p>A dim every result agrees on distinguishes nothing — if all five are
     * jazz, printing "jazz" five times helps no one choose. Selecting the
     * columns with the most variance is arithmetic, not a model call, which is
     * the point: the reader sees the difference rather than being told a story
     * about it.
     */
    private Set<String> dimsThatVary(Map<String, List<EventFacet>> facetsByEvent) {
        if (facetsByEvent.size() < 2) {
            return facetsByEvent.values().stream()
                    .flatMap(List::stream).map(EventFacet::getDim)
                    .collect(Collectors.toSet());
        }

        Map<String, Set<String>> valuesByDim = new HashMap<>();
        facetsByEvent.values().stream().flatMap(List::stream).forEach(f ->
                valuesByDim.computeIfAbsent(f.getDim(), k -> new HashSet<>()).add(f.getValue()));

        return valuesByDim.entrySet().stream()
                .filter(en -> en.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
