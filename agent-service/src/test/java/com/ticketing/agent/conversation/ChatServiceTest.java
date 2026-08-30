package com.ticketing.agent.conversation;

import com.ticketing.agent.conversation.ConversationState.Stage;
import com.ticketing.agent.domain.model.AgentEvent;
import com.ticketing.agent.domain.repository.AgentEventRepository;
import com.ticketing.agent.search.*;
import com.ticketing.agent.search.QueryExtraction.Intent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The turn-to-turn behaviour that four separate bugs got wrong before anything
 * tested it.
 *
 * <p>Every case below reproduces a failure found by running a five-turn
 * conversation by hand. None of them would have survived a test, and all four
 * shipped because the sixteen tests that existed covered validation and
 * taxonomy — the parts that were already correct.
 */
class ChatServiceTest {

    private ConversationStore    store;
    private QueryExtractor       extractor;
    private SearchService        searchService;
    private AgentEventRepository eventRepository;
    private ChatService          chat;

    private final Map<String, ConversationState> saved = new HashMap<>();

    @BeforeEach
    void setUp() {
        store           = mock(ConversationStore.class);
        extractor       = mock(QueryExtractor.class);
        searchService   = mock(SearchService.class);
        eventRepository = mock(AgentEventRepository.class);
        chat = new ChatService(store, extractor, searchService, eventRepository);

        // A store that actually remembers, so a test can drive several turns.
        when(store.load(anyString()))
                .thenAnswer(i -> Optional.ofNullable(saved.get(i.getArgument(0))));
        doAnswer(i -> {
            ConversationState s = i.getArgument(0);
            saved.put(s.getSessionId(), s);
            return null;
        }).when(store).save(any());

        when(searchService.search(any(QueryExtraction.class), any()))
                .thenReturn(new SearchResult(List.of(), 0, List.of(), false));
    }

    private static AgentEvent event(String id, String name) {
        return AgentEvent.builder().id(id).name(name).status("OPEN")
                .startAt(Instant.now().plusSeconds(86_400)).build();
    }

    private static QueryExtraction patch(Intent intent, Integer ordinal, String city,
                                         BigDecimal price, List<String> clear,
                                         List<FacetQuery> vibe) {
        return new QueryExtraction(intent, ordinal, clear, null, city, null, price,
                vibe, List.of());
    }

    @Test
    @DisplayName("a hard slot survives the next turn")
    void hardSlotsPersist() {
        // The worst of the four bugs. State was reassembled into a sentence and
        // re-extracted every turn, so a city captured on turn one was gone by
        // turn two — the rebuilt sentence no longer parsed as one with a city,
        // and the merge overwrote the slot with the null it got back.
        when(extractor.extract("a musical in london"))
                .thenReturn(patch(Intent.FIND, null, "london", null, List.of(),
                        List.of(new FacetQuery("format", "staged musical"))));
        when(extractor.extract("something cheaper"))
                .thenReturn(patch(Intent.FIND, null, null, new BigDecimal("50"),
                        List.of(), List.of()));

        chat.handle("s1", "a musical in london", null);
        var turn2 = chat.handle("s1", "something cheaper", null);

        assertThat(turn2.state().getCity())
                .as("a city stated on turn one must still be set on turn two")
                .isEqualTo("london");
        assertThat(turn2.state().getPriceMax()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("silence does not clear a slot, but an explicit retraction does")
    void silenceIsNotRetraction() {
        when(extractor.extract("in london"))
                .thenReturn(patch(Intent.FIND, null, "london", null, List.of(), List.of()));
        when(extractor.extract("anything on saturday"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(), List.of()));
        when(extractor.extract("anywhere is fine"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of("city"), List.of()));

        chat.handle("s2", "in london", null);
        assertThat(chat.handle("s2", "anything on saturday", null).state().getCity())
                .as("not mentioning a city is not the same as removing it")
                .isEqualTo("london");
        assertThat(chat.handle("s2", "anywhere is fine", null).state().getCity())
                .as("an explicit retraction clears the slot")
                .isNull();
    }

    @Test
    @DisplayName("an ordinal selects from what the last turn showed")
    void ordinalSelectsFromLastResults() {
        AgentEvent second = event("evt-2", "The Lion King");
        when(searchService.search(any(QueryExtraction.class), any())).thenReturn(
                new SearchResult(List.of(
                        new SearchResult.Scored(event("evt-1", "Hamilton"), 0.9, 0.5),
                        new SearchResult.Scored(second, 0.8, 0.4)), 2, List.of(), true));
        when(eventRepository.findById("evt-2")).thenReturn(Optional.of(second));

        when(extractor.extract("musicals"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(), List.of()));
        when(extractor.extract("the second one"))
                .thenReturn(patch(Intent.SELECT, 2, null, null, List.of(), List.of()));

        chat.handle("s3", "musicals", null);
        var picked = chat.handle("s3", "the second one", null);

        assertThat(picked.state().getStage()).isEqualTo(Stage.FOCUSED);
        assertThat(picked.focused()).isNotNull();
        assertThat(picked.focused().getId())
                .as("the position is resolved by index, never by asking the model")
                .isEqualTo("evt-2");
    }

    @Test
    @DisplayName("selecting a row never clears a filter")
    void selectionDoesNotRetract() {
        // qwen3:8b answers "the second one" with ordinal 2 AND
        // clearFields ["city","dateExpression","priceMax"] — reading the
        // absence of those words as a request to drop them. Prompting did not
        // stop it, so the invariant is enforced in the type: a selection
        // retracts nothing.
        AgentEvent first = event("evt-1", "Hamilton");
        when(searchService.search(any(QueryExtraction.class), any())).thenReturn(
                new SearchResult(List.of(new SearchResult.Scored(first, 0.9, 0.5)),
                        1, List.of(), true));
        when(eventRepository.findById("evt-1")).thenReturn(Optional.of(first));

        when(extractor.extract("musicals in london"))
                .thenReturn(patch(Intent.FIND, null, "london", null, List.of(), List.of()));
        when(extractor.extract("the first one"))
                .thenReturn(patch(Intent.SELECT, 1, null, null,
                        List.of("city", "priceMax"), List.of()));

        chat.handle("s4", "musicals in london", null);
        var picked = chat.handle("s4", "the first one", null);

        assertThat(picked.state().getCity())
                .as("pointing at a row must not wipe the conversation's filters")
                .isEqualTo("london");
    }

    @Test
    @DisplayName("a question about the chosen event does not start a new search")
    void detailStaysFocused() {
        // "what time does it start" produced a stray duration facet, which the
        // old isBare() test read as a new search — silently losing the event
        // the person had just picked.
        AgentEvent chosen = event("evt-1", "Dua Lipa");
        when(searchService.search(any(QueryExtraction.class), any())).thenReturn(
                new SearchResult(List.of(new SearchResult.Scored(chosen, 0.9, 0.5)),
                        1, List.of(), true));
        when(eventRepository.findById("evt-1")).thenReturn(Optional.of(chosen));

        when(extractor.extract("concerts"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(), List.of()));
        when(extractor.extract("the first one"))
                .thenReturn(patch(Intent.SELECT, 1, null, null, List.of(), List.of()));
        when(extractor.extract("what time does it start"))
                .thenReturn(patch(Intent.DETAIL, null, null, null, List.of(),
                        List.of(new FacetQuery("duration", "start time"))));

        chat.handle("s5", "concerts", null);
        chat.handle("s5", "the first one", null);
        clearInvocations(searchService);

        var detail = chat.handle("s5", "what time does it start", null);

        assertThat(detail.state().getStage()).isEqualTo(Stage.FOCUSED);
        assertThat(detail.focused()).isNotNull();
        verify(searchService, never()).search(any(QueryExtraction.class), any());
    }

    @Test
    @DisplayName("a new search leaves focus, and says so")
    void searchingLeavesFocus() {
        // Leaving the stage untouched on a search reported FOCUSED alongside a
        // fresh result list — two different answers to "where am I".
        AgentEvent chosen = event("evt-1", "Dua Lipa");
        when(searchService.search(any(QueryExtraction.class), any())).thenReturn(
                new SearchResult(List.of(new SearchResult.Scored(chosen, 0.9, 0.5)),
                        1, List.of(), true));
        when(eventRepository.findById("evt-1")).thenReturn(Optional.of(chosen));

        when(extractor.extract("concerts"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(), List.of()));
        when(extractor.extract("the first one"))
                .thenReturn(patch(Intent.SELECT, 1, null, null, List.of(), List.of()));
        when(extractor.extract("something in tokyo instead"))
                .thenReturn(patch(Intent.FIND, null, "tokyo", null, List.of(), List.of()));

        chat.handle("s6", "concerts", null);
        chat.handle("s6", "the first one", null);
        var moved = chat.handle("s6", "something in tokyo instead", null);

        assertThat(moved.state().getStage()).isEqualTo(Stage.BROWSING);
        assertThat(moved.state().getFocusedEventId()).isNull();
        assertThat(moved.focused()).isNull();
    }

    @Test
    @DisplayName("a mood replaces the previous one instead of accumulating")
    void vibeReplacesRatherThanAppends() {
        // Appending would leave a query vector sitting between two opposites,
        // matching neither. Silence still leaves the previous mood standing, so
        // "in london" narrows a mood rather than erasing it.
        when(extractor.extract("something relaxing"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(),
                        List.of(new FacetQuery("atmosphere", "calm, unhurried"))));
        when(extractor.extract("in london"))
                .thenReturn(patch(Intent.FIND, null, "london", null, List.of(), List.of()));
        when(extractor.extract("actually more upbeat"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(),
                        List.of(new FacetQuery("atmosphere", "lively, high energy"))));

        chat.handle("s7", "something relaxing", null);
        assertThat(chat.handle("s7", "in london", null).state().getVibeFacets())
                .as("a turn with no mood leaves the previous one in place")
                .containsExactly("atmosphere|calm, unhurried");

        assertThat(chat.handle("s7", "actually more upbeat", null).state().getVibeFacets())
                .as("a turn with a mood replaces it entirely")
                .containsExactly("atmosphere|lively, high energy");
    }

    @Test
    @DisplayName("an out-of-range ordinal falls back to searching")
    void outOfRangeOrdinalDoesNotError() {
        when(extractor.extract("concerts"))
                .thenReturn(patch(Intent.FIND, null, null, null, List.of(), List.of()));
        when(extractor.extract("the ninth one"))
                .thenReturn(patch(Intent.SELECT, 9, null, null, List.of(), List.of()));

        chat.handle("s8", "concerts", null);
        var out = chat.handle("s8", "the ninth one", null);

        assertThat(out.state().getStage())
                .as("pointing past the end is not an error the person should see")
                .isEqualTo(Stage.BROWSING);
        assertThat(out.focused()).isNull();
    }
}
