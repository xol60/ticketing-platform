package com.ticketing.gateway.cache;

import com.ticketing.gateway.config.GatewayProperties;
import com.ticketing.gateway.security.TokenIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * L1 cache: in-process LRU map.
 *
 * Characteristics:
 *  - Sub-millisecond lookup (no network)
 *  - Pod-local: each gateway pod has its own L1
 *  - Short TTL (default 30 s) to bound revocation propagation lag
 *  - Fixed capacity with LRU eviction to cap memory usage
 */
@Slf4j
@Component
public class L1TokenCache {

    private final long ttlSeconds;
    private final Map<String, CacheEntry> store;

    public L1TokenCache(GatewayProperties properties) {
        this.ttlSeconds = properties.getCache().getL1TtlSeconds();
        int maxSize = properties.getCache().getL1MaxSize();

        // Thread-safe LRU via synchronized LinkedHashMap
        this.store = java.util.Collections.synchronizedMap(
                new LinkedHashMap<>(maxSize, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return size() > maxSize;
                    }
                }
        );
    }

    public Optional<TokenIdentity> get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            store.remove(key);
            log.debug("L1 cache entry expired for key={}", key);
            return Optional.empty();
        }
        log.debug("L1 cache hit for key={}", key);
        return Optional.of(entry.identity());
    }

    public void put(String key, TokenIdentity identity) {
        Instant expiresAt = Instant.now().plusSeconds(
                Math.min(ttlSeconds, identity.remainingTtlSeconds())
        );
        store.put(key, new CacheEntry(identity, expiresAt));
        log.debug("L1 cache stored key={}", key);
    }

    public void invalidate(String key) {
        store.remove(key);
        log.debug("L1 cache invalidated key={}", key);
    }

    public int size() {
        return store.size();
    }

    private record CacheEntry(TokenIdentity identity, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
