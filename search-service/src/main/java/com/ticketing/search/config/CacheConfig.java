package com.ticketing.search.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In-process Caffeine cache for the autocomplete /suggest endpoint.
 *
 * <h2>Why only suggest is cached (and not full /search)</h2>
 *
 * Autocomplete keys are short prefixes — {@code "co"}, {@code "col"},
 * {@code "cold"}, {@code "coldp"} … — naturally low cardinality with very
 * high reuse across users on the same hot prefix. Caching it collapses
 * what would be N redundant ES queries into one per 60 s window.
 *
 * <p>Full-search queries, on the other hand, have very high cardinality
 * (free-text input + facets + paging) and low reuse per key, so caching
 * them risks polluting the cache with one-shot junk while delivering very
 * little hit ratio. We deliberately leave the {@code search()} path
 * uncached and rely on the gateway rate limit + client debounce for
 * volume control there.
 *
 * <h2>Eviction</h2>
 *
 * Caffeine's default admission policy is <b>W-TinyLFU</b>, a
 * frequency-aware eviction algorithm that uses a probabilistic frequency
 * sketch to decide which entries to keep. The relevant guarantee: a
 * one-shot junk query like {@code "1234567890"} cannot evict a hot prefix
 * like {@code "coldplay"} that has been seen many times, because W-TinyLFU
 * compares observed frequencies before admitting a new entry — not just
 * recency (which would let LRU thrash under junk traffic).
 *
 * <h2>Sizing</h2>
 *
 * 50 000 entries × ~1 KB each ≈ 50 MB heap. Trivial. We over-provision on
 * purpose so the working set easily fits even at peak diversity.
 *
 * <h2>Freshness</h2>
 *
 * 60 s TTL is the worst-case staleness floor. In practice, every event
 * change flows through {@link com.ticketing.search.kafka.EventIndexConsumer},
 * which calls {@code cache.invalidate()} after the ES upsert/delete
 * succeeds — so users see edits within Kafka-latency (~1-2 s), not 60 s.
 * The TTL is a safety net, not the freshness guarantee.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Public so callers (the Kafka consumer) can ask the manager to invalidate. */
    public static final String SUGGEST_REGION = "search-suggest";

    @Bean
    public CacheManager cacheManager() {
        var suggest = new CaffeineCache(SUGGEST_REGION,
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(50_000)
                        .recordStats()      // exposes hit ratio via /actuator/metrics/cache.*
                        .build());

        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(suggest));
        return manager;
    }
}
