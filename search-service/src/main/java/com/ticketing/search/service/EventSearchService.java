package com.ticketing.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.ticketing.common.events.EventStatus;
import com.ticketing.search.config.CacheConfig;
import com.ticketing.search.domain.model.EventDocument;
import com.ticketing.search.dto.response.AutocompleteSuggestion;
import com.ticketing.search.dto.response.EventSearchHit;
import com.ticketing.search.dto.response.EventSearchPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Search query builder + executor. The three "features that justify ES" all
 * live here.
 *
 * <h3>1. Multi-field full-text search with boosting</h3>
 * {@link #search(String, String, String, int, int)} builds a {@code multi_match}
 * query over six fields with hand-tuned boosts:
 * <pre>
 *   name              ^ 3.0     // strongest signal — exact event identifier
 *   primaryArtist     ^ 2.5     // searches like "coldplay" should hit artist
 *   venueName         ^ 1.5     // "madison square garden" → venue
 *   venueCity         ^ 1.0     // base weight, broad disambiguator
 *   shortDescription  ^ 0.8     // marketing copy, weaker signal
 *   fullDescription   ^ 0.4     // long-form copy, weakest signal
 * </pre>
 * Boosts are multiplicative on the BM25 score, so a {@code name} hit always
 * outranks a {@code venueCity} hit of similar term frequency.
 *
 * <h3>2. Fuzzy matching</h3>
 * Same query uses {@code fuzziness=AUTO}, which ES translates into:
 * <ul>
 *   <li>length 0–2: exact match required</li>
 *   <li>length 3–5: 1 edit allowed (one typo)</li>
 *   <li>length 6+:  2 edits allowed</li>
 * </ul>
 * So {@code coldlpay} → {@code Coldplay} works without the user noticing.
 *
 * <h3>3. Autocomplete</h3>
 * {@link #suggest(String, int)} hits the {@code name.edge} sub-field whose
 * tokens are produced by the custom {@code edge_ngram_analyzer} (min_gram=2).
 * Typing two characters is enough to start surfacing matches.
 *
 * <h3>Status filter</h3>
 * Both queries enforce {@code status = OPEN} as a filter clause. We rely on
 * the indexing pipeline to also delete non-OPEN events, so this is a
 * belt-and-braces guard — the index should never contain a non-OPEN doc, but
 * if a stale one ever slipped in we still wouldn't surface it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSearchService {

    private final ElasticsearchOperations operations;

    /**
     * Multi-field search with boosting + fuzzy. Optional category / genre
     * filters narrow the result set without affecting relevance scoring.
     *
     * @param queryText user-typed query (free text, may contain typos)
     * @param category  optional CONCERT | SPORTS | THEATER | CONFERENCE filter
     * @param genre     optional genre filter (POP, ROCK, EDM, JAZZ ...)
     * @param from      zero-based start offset (for pagination)
     * @param size      page size
     */
    public EventSearchPage search(String queryText, String category, String genre,
                                   int from, int size) {

        final int safeSize = Math.min(Math.max(size, 1), 100);
        final int safeFrom = Math.max(from, 0);
        final int page     = safeFrom / safeSize;

        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        // ── Relevance: multi-match across six fields ──
                        .must(m -> m.multiMatch(mm -> mm
                                .query(queryText)
                                .fields("name^3.0",
                                        "primaryArtist^2.5",
                                        "venueName^1.5",
                                        "venueCity^1.0",
                                        "shortDescription^0.8",
                                        "fullDescription^0.4")
                                .type(TextQueryType.BestFields)
                                .fuzziness("AUTO")))
                        // ── Hard filter: only OPEN events are searchable ──
                        .filter(f -> f.term(t -> t
                                .field("status")
                                .value(EventStatus.OPEN.name())))
                        // ── Optional facet filters ──
                        .filter(f -> {
                            if (category != null && !category.isBlank()) {
                                return f.term(t -> t.field("category").value(category));
                            }
                            return f.matchAll(ma -> ma);
                        })
                        .filter(f -> {
                            if (genre != null && !genre.isBlank()) {
                                return f.term(t -> t.field("genre").value(genre));
                            }
                            return f.matchAll(ma -> ma);
                        })
                ))
                .withPageable(PageRequest.of(page, safeSize))
                .build();

        SearchHits<EventDocument> hits = operations.search(query, EventDocument.class);

        List<EventSearchHit> results = hits.stream()
                .map(EventSearchService::toHit)
                .toList();

        return EventSearchPage.builder()
                .query(queryText)
                .totalHits(hits.getTotalHits())
                .from(safeFrom)
                .size(safeSize)
                .hits(results)
                .build();
    }

    /**
     * Autocomplete-as-you-type. Designed to be hit on every keystroke from
     * the second character on — the {@code name.edge} sub-field is indexed
     * with edge_ngrams so a 2-char prefix already matches.
     *
     * <p>Restricted to OPEN events. Capped at 10 results to keep the dropdown
     * tight and the response small.
     *
     * <h3>Caching</h3>
     * Results are cached in the Caffeine {@code search-suggest} region
     * (60 s TTL, 50 000 entries, W-TinyLFU eviction). Three SpEL knobs
     * shape what gets cached:
     *
     * <ul>
     *   <li><b>{@code key}</b> uses {@link SearchKey#normalise(String)} so
     *       {@code "Co"}, {@code "co"}, {@code "co  "} all share the same
     *       entry. Size is part of the key because asking for 6 vs 10
     *       suggestions returns different rows.</li>
     *   <li><b>{@code condition}</b> uses {@link SearchKey#cacheable(String)}
     *       to bypass the cache entirely for pathological inputs (too
     *       short, too long, or no letters). Such requests still produce
     *       a correct answer — they just don't burn a cache slot.</li>
     *   <li><b>{@code unless}</b> declines to cache nulls (the cache
     *       interceptor would explode on a null otherwise; we always
     *       return a non-null list in practice but the guard is cheap
     *       insurance against future refactors).</li>
     * </ul>
     *
     * <p>Cache freshness is enforced by
     * {@link com.ticketing.search.kafka.EventIndexConsumer} which calls
     * {@code cache.invalidate()} after every successful ES write — so
     * users see edits within Kafka latency (~1-2 s), not after the
     * 60 s TTL expires.
     */
    @Cacheable(
            value     = CacheConfig.SUGGEST_REGION,
            key       = "T(com.ticketing.search.service.SearchKey).normalise(#prefix) + ':' + #size",
            condition = "T(com.ticketing.search.service.SearchKey).cacheable(#prefix)",
            unless    = "#result == null")
    public List<AutocompleteSuggestion> suggest(String prefix, int size) {
        final int safeSize = Math.min(Math.max(size, 1), 10);

        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mm -> mm
                                .field("name.edge")
                                .query(prefix)))
                        .filter(f -> f.term(t -> t
                                .field("status")
                                .value(EventStatus.OPEN.name())))
                ))
                .withPageable(PageRequest.of(0, safeSize))
                .build();

        SearchHits<EventDocument> hits = operations.search(query, EventDocument.class);

        return hits.stream()
                .map(h -> AutocompleteSuggestion.builder()
                        .eventId(h.getContent().getId())
                        .text(h.getContent().getName())
                        .primaryArtist(h.getContent().getPrimaryArtist())
                        .build())
                .toList();
    }

    private static EventSearchHit toHit(SearchHit<EventDocument> h) {
        EventDocument d = h.getContent();
        return EventSearchHit.builder()
                .id(d.getId())
                .name(d.getName())
                .primaryArtist(d.getPrimaryArtist())
                .venueName(d.getVenueName())
                .venueCity(d.getVenueCity())
                .category(d.getCategory())
                .genre(d.getGenre())
                .eventDate(d.getEventDate())
                .score(h.getScore())
                .build();
    }
}
