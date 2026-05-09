package com.ticketing.gateway.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Two-layer cache for token generation counters (one counter per userId).
 *
 * Layer 1 — in-process map, TTL 5 s.
 *   A short TTL is intentional: when a security event increments the counter in
 *   Redis, stale gateway pods must not serve compromised tokens for more than 5 s.
 *   (Compare: the token L1 cache has a 30 s TTL because revocation lag there is
 *    acceptable; here we tighten it because a stolen token could be active.)
 *
 * Layer 2 — Redis key "token_gen:{userId}", written by auth-service.
 *   Missing key → generation 0 (no security event has ever fired for this user).
 */
@Slf4j
@Component
public class TokenGenCache {

    private static final String  GEN_KEY_PREFIX = "token_gen:";
    private static final long    L1_TTL_SECONDS = 5L;
    private static final int     MAX_SIZE       = 10_000;

    // Thread-safe map; size-bounded to prevent unbounded growth under high cardinality
    private final Map<String, GenEntry> store = Collections.synchronizedMap(
            new HashMap<>(MAX_SIZE)
    );

    private final ReactiveStringRedisTemplate stringTemplate;

    public TokenGenCache(ReactiveStringRedisTemplate stringTemplate) {
        this.stringTemplate = stringTemplate;
    }

    /**
     * Returns the current generation for the given userId.
     * Checks L1 first; falls back to Redis on miss or expiry.
     */
    public Mono<Long> getGeneration(String userId) {
        GenEntry entry = store.get(userId);
        if (entry != null && !entry.isExpired()) {
            log.debug("TokenGenCache L1 hit userId={} gen={}", userId, entry.gen());
            return Mono.just(entry.gen());
        }

        return stringTemplate.opsForValue()
                .get(GEN_KEY_PREFIX + userId)
                .map(Long::parseLong)
                .defaultIfEmpty(0L)
                .doOnNext(gen -> {
                    store.put(userId, new GenEntry(gen,
                            Instant.now().plusSeconds(L1_TTL_SECONDS)));
                    log.debug("TokenGenCache L2 hit userId={} gen={}", userId, gen);
                });
    }

    /** Evict a userId entry so the next request forces a fresh Redis read. */
    public void invalidate(String userId) {
        store.remove(userId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private record GenEntry(long gen, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
