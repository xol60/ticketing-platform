package com.ticketing.search.service;

import com.ticketing.search.domain.model.EventDocument;
import com.ticketing.search.dto.response.AutocompleteSuggestion;
import com.ticketing.search.dto.response.EventSearchHit;
import com.ticketing.search.dto.response.EventSearchPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural contract for {@link EventSearchService}.
 *
 * <p>Two end-user-visible properties under test:
 *
 * <ol>
 *   <li><b>The service issues an ES query at all</b> — through the
 *       {@link ElasticsearchOperations} bean (so plugging in a real cluster
 *       is the only swap). We capture the {@link NativeQuery} via
 *       {@link ArgumentCaptor} and assert it's a non-null {@code NativeQuery}
 *       with an attached body — proves the builder ran. Asserting on the
 *       serialised DSL string was attempted but is brittle across Spring Data
 *       ES + ES Java client minor versions, so we focus on observable mapping
 *       and paging behaviour instead.</li>
 *   <li><b>Hit-to-DTO mapping</b> — every searchable field on
 *       {@link EventDocument} is faithfully copied into either
 *       {@link EventSearchHit} (search) or {@link AutocompleteSuggestion}
 *       (suggest), and the {@code score} is carried through. A regression
 *       that dropped a field would corrupt the search-results UI silently.</li>
 * </ol>
 *
 * <p>{@code @MockitoSettings(strictness = LENIENT)} because the tests use
 * shared SearchHits mock helpers — some stubbed methods on those helpers
 * aren't needed by every test, and the test class would otherwise fail on
 * Mockito's strict-stubbing check.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EventSearchService — query + mapping contract")
class EventSearchServiceTest {

    @Mock ElasticsearchOperations operations;

    EventSearchService service;

    @BeforeEach
    void setUp() {
        service = new EventSearchService(operations);
    }

    @Test
    @DisplayName("search(...) builds and submits a NativeQuery against the events index")
    void search_submitsNativeQuery() {
        SearchHits<EventDocument> mocked = emptyHits();
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        service.search("coldplay", null, null, 0, 20);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(operations).search(captor.capture(), eq(EventDocument.class));

        Query submitted = captor.getValue();
        assertThat(submitted).isInstanceOf(NativeQuery.class);

        // The NativeQuery must carry a real query body — without it we'd be
        // running an unbounded match-all under the covers.
        NativeQuery nq = (NativeQuery) submitted;
        assertThat(nq.getQuery()).isNotNull();
        assertThat(nq.getPageable()).isNotNull();
    }

    @Test
    @DisplayName("search(...) maps each SearchHit field into EventSearchHit and echoes paging metadata")
    void search_mapsHitsAndPagingMetadata() {
        EventDocument doc = EventDocument.builder()
                .id("evt-1")
                .name("Hello Tour")
                .primaryArtist("Adele")
                .venueName("Wembley")
                .venueCity("London")
                .category("CONCERT")
                .genre("POP")
                .eventDate(Instant.parse("2026-09-01T19:00:00Z"))
                .status("OPEN")
                .build();
        SearchHits<EventDocument> mocked = singleHit(doc, 1.42f);
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        EventSearchPage page = service.search("adele", null, null, 0, 20);

        assertThat(page.getQuery()).isEqualTo("adele");
        assertThat(page.getTotalHits()).isEqualTo(1L);
        assertThat(page.getFrom()).isEqualTo(0);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getHits()).hasSize(1);

        EventSearchHit hit = page.getHits().get(0);
        assertThat(hit.getId()).isEqualTo("evt-1");
        assertThat(hit.getName()).isEqualTo("Hello Tour");
        assertThat(hit.getPrimaryArtist()).isEqualTo("Adele");
        assertThat(hit.getVenueName()).isEqualTo("Wembley");
        assertThat(hit.getVenueCity()).isEqualTo("London");
        assertThat(hit.getCategory()).isEqualTo("CONCERT");
        assertThat(hit.getGenre()).isEqualTo("POP");
        assertThat(hit.getScore()).isEqualTo(1.42f);
    }

    @Test
    @DisplayName("search(...) returns empty hits cleanly when ES has nothing for the query")
    void search_emptyResultsAreSafe() {
        SearchHits<EventDocument> mocked = emptyHits();
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        EventSearchPage page = service.search("zzzzz-nothing", null, null, 0, 20);

        assertThat(page.getTotalHits()).isEqualTo(0L);
        assertThat(page.getHits()).isEmpty();
    }

    @Test
    @DisplayName("search(...) clamps size to [1, 100] — defensive against abusive page sizes")
    void search_clampsSizeUpperBound() {
        SearchHits<EventDocument> mocked = emptyHits();
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        EventSearchPage page = service.search("q", null, null, 0, 99999);
        assertThat(page.getSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("search(...) clamps size up to 1 when caller passes 0 or negative")
    void search_clampsSizeLowerBound() {
        SearchHits<EventDocument> mocked = emptyHits();
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        EventSearchPage page = service.search("q", null, null, 0, 0);
        assertThat(page.getSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("suggest(...) returns AutocompleteSuggestion DTOs with id + text + artist")
    void suggest_mapsToSuggestionDto() {
        EventDocument doc = EventDocument.builder()
                .id("evt-7")
                .name("Coldplay World Tour")
                .primaryArtist("Coldplay")
                .build();
        SearchHits<EventDocument> mocked = singleHit(doc, 2.0f);
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        List<AutocompleteSuggestion> suggestions = service.suggest("col", 5);

        assertThat(suggestions).hasSize(1);
        AutocompleteSuggestion s = suggestions.get(0);
        assertThat(s.getEventId()).isEqualTo("evt-7");
        assertThat(s.getText()).isEqualTo("Coldplay World Tour");
        assertThat(s.getPrimaryArtist()).isEqualTo("Coldplay");
    }

    @Test
    @DisplayName("suggest(...) submits a NativeQuery (so it hits the configured edge_ngram analyzer)")
    void suggest_submitsNativeQuery() {
        SearchHits<EventDocument> mocked = emptyHits();
        when(operations.search(any(Query.class), eq(EventDocument.class))).thenReturn(mocked);

        service.suggest("co", 5);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(operations).search(captor.capture(), eq(EventDocument.class));
        assertThat(captor.getValue()).isInstanceOf(NativeQuery.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build an empty {@link SearchHits} via Mockito.
     * Avoids coupling the test to {@code SearchHitsImpl}'s constructor signature,
     * which drifts between Spring Data ES minor versions.
     *
     * <p>{@code stream()} is wired via {@code thenAnswer} (not {@code thenReturn})
     * so each invocation returns a fresh terminal stream — a single
     * {@code Stream.empty()} can only be consumed once.
     */
    @SuppressWarnings("unchecked")
    private static SearchHits<EventDocument> emptyHits() {
        SearchHits<EventDocument> hits = (SearchHits<EventDocument>) mock(SearchHits.class);
        when(hits.getTotalHits()).thenReturn(0L);
        when(hits.stream()).thenAnswer(inv -> Stream.empty());
        return hits;
    }

    @SuppressWarnings("unchecked")
    private static SearchHits<EventDocument> singleHit(EventDocument doc, float score) {
        SearchHit<EventDocument> hit = (SearchHit<EventDocument>) mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        when(hit.getScore()).thenReturn(score);

        SearchHits<EventDocument> hits = (SearchHits<EventDocument>) mock(SearchHits.class);
        when(hits.getTotalHits()).thenReturn(1L);
        when(hits.stream()).thenAnswer(inv -> Stream.of(hit));
        return hits;
    }
}
