package com.ticketing.search.service;

import java.util.Locale;

/**
 * Cache-key utilities for the autocomplete /suggest endpoint.
 *
 * <p>Exposed as static methods so that Spring's SpEL can call them from
 * {@code @Cacheable(key = …, condition = …)} expressions on
 * {@link EventSearchService#suggest(String, int)}.
 *
 * <h3>Why this lives in its own class</h3>
 * Cache-key derivation and admission policy are pure, side-effect-free
 * functions of the input. Keeping them out of the service class means we
 * can unit-test them in isolation, reuse them from other call sites if
 * needed, and reason about cache behaviour without reading the search
 * implementation.
 *
 * <h3>Two responsibilities</h3>
 *
 * <ul>
 *   <li><b>{@link #normalise(String)}</b> — lowercase + trim + collapse
 *       internal whitespace. Collapses {@code "Co"}, {@code "co"},
 *       {@code "co  "}, {@code "  CO  "} into the single key entry
 *       {@code "co"}. Reduces cache-key cardinality with zero behaviour
 *       change (ES's standard analyzer already lowercases).</li>
 *
 *   <li><b>{@link #cacheable(String)}</b> — admission filter for the cache.
 *       Returns {@code false} for inputs that would just pollute it:
 *       length outside [2, 64], or no alphabetic character (pure digits,
 *       pure punctuation, etc.). Such inputs are still searched on every
 *       call — they just bypass the cache entirely so they can't crowd
 *       out hot prefixes. (Caffeine's W-TinyLFU already resists this kind
 *       of pollution, but rejecting at the door is even cheaper.)</li>
 * </ul>
 */
public final class SearchKey {

    private SearchKey() {}

    /** Min length we'll cache. Matches the UI's 2-char autocomplete threshold. */
    private static final int MIN_LEN = 2;

    /** Max length we'll cache. Anything longer is almost certainly junk
     *  (pasted UUIDs, stack traces, fuzzed inputs). 64 chars covers every
     *  reasonable event-name prefix with margin to spare. */
    private static final int MAX_LEN = 64;

    /**
     * Normalise a prefix to its canonical cache key form.
     *
     * <p>{@code Locale.ROOT} on toLowerCase to avoid the Turkish-i trap
     * (default locale would map 'I' to 'ı' on a tr-TR JVM, splintering
     * cache keys). Whitespace is squeezed to single spaces so
     * {@code "led   zep"} and {@code "led zep"} share one cache entry.
     */
    public static String normalise(String prefix) {
        if (prefix == null) return "";
        return prefix
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    /**
     * Should this input be admitted to the cache at all?
     *
     * <p>Rejects:
     * <ul>
     *   <li>{@code null} / empty / blank inputs</li>
     *   <li>Anything shorter than {@link #MIN_LEN} or longer than {@link #MAX_LEN}
     *       (measured AFTER normalisation, so trailing whitespace doesn't
     *       inflate the count)</li>
     *   <li>Inputs with no alphabetic characters — pure digits like
     *       "12345" or pure punctuation are almost always one-shot junk
     *       searches. Letting them into the cache wastes a slot without
     *       producing a reusable entry.</li>
     * </ul>
     *
     * Pathological inputs still produce a correct result — they just take
     * the un-cached path through Elasticsearch.
     */
    public static boolean cacheable(String prefix) {
        String n = normalise(prefix);
        int len = n.length();
        if (len < MIN_LEN || len > MAX_LEN) return false;

        for (int i = 0; i < len; i++) {
            if (Character.isLetter(n.charAt(i))) return true;
        }
        return false;
    }
}
