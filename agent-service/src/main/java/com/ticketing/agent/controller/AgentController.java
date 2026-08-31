package com.ticketing.agent.controller;

import com.ticketing.agent.domain.model.EventFacet;
import com.ticketing.agent.domain.repository.EventFacetRepository;
import com.ticketing.agent.conversation.ChatService;
import com.ticketing.agent.conversation.ConversationState;
import com.ticketing.agent.dto.ChatRequest;
import com.ticketing.agent.dto.ChatResponse;
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
    private final ChatService          chatService;
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
                .matchedCount(result.matchedCount())
                .totalMatched(result.totalMatched())
                .offerNarrowing(result.totalMatched() > SearchService.NARROW_THRESHOLD)
                .relaxations(result.relaxations())
                .usedVibe(result.usedVibe())
                .build());
    }

    /**
     * One conversational turn.
     *
     * <p>Layered on the same retrieval path as {@link #search}, not a parallel
     * one. Memory is the only thing added — which keeps the P2 eval numbers
     * describing what actually runs, and means a regression in ranking shows up
     * in both endpoints rather than hiding in one.
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatService.TurnResult turn = chatService.handle(
                request.getSessionId(), request.getMessage(), request.getCity());

        ConversationState st = turn.state();
        var builder = ChatResponse.builder()
                .stage(st.getStage().name())
                .activeFilters(ChatResponse.ActiveFilters.builder()
                        .city(st.getCity())
                        .dateExpression(st.getDateExpression())
                        .priceMax(st.getPriceMax() == null ? null : st.getPriceMax().toPlainString())
                        .excludeTags(st.getExcludeTags())
                        .vibe(st.getVibeFacets())
                        .turnCount(st.getTurnCount())
                        .build());

        if (turn.handoff() != null) {
            var h = turn.handoff();
            builder.handoff(ChatResponse.HandoffInfo.builder()
                    .eventId(h.eventId()).deepLink(h.deepLink())
                    .available(h.available()).reason(h.reason()).build());
        }

        if (turn.focused() != null) {
            builder.focused(toHits(new SearchResult(
                    List.of(new SearchResult.Scored(turn.focused(), 1.0, 0.0)),
                    1, List.of(), false)).get(0));
        } else if (turn.search() != null) {
            var r = turn.search();
            builder.hits(toHits(r))
                    .matchedCount(r.matchedCount())
                   .totalMatched(r.totalMatched())
                   .offerNarrowing(r.totalMatched() > SearchService.NARROW_THRESHOLD)
                   .relaxations(r.relaxations())
                   .usedVibe(r.usedVibe());
        }

        return ApiResponse.ok(builder.build());
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
                    .matched(s.matched())
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
