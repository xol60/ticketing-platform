package com.ticketing.gateway.cache;

import com.ticketing.gateway.security.TokenIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * L2 cache: Redis distributed store shared across all gateway pods.
 *
 * Characteristics:
 *  - ~1 ms network hop (vs 0 ms L1)
 *  - Shared state: revocation propagates immediately across pods
 *  - TTL = remaining JWT lifetime, so entries auto-expire with their tokens
 *  - On hit: writes back to L1 so next request from same pod is free
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class L2TokenCache {

    private static final String REVOCATION_PREFIX = "revoked:";

    private final ReactiveRedisTemplate<String, TokenIdentity> tokenRedisTemplate;
    private final ReactiveRedisTemplate<String, String>        stringTemplate;

    public Mono<TokenIdentity> get(String key) {
        return tokenRedisTemplate.opsForValue().get(key)
                .doOnNext(v -> log.debug("L2 cache hit for key={}", key))
                .switchIfEmpty(Mono.fromRunnable(() -> log.debug("L2 cache miss for key={}", key)))
                .cast(TokenIdentity.class);
    }

    public Mono<Boolean> put(String key, TokenIdentity identity) {
        long ttl = identity.remainingTtlSeconds();
        if (ttl <= 0) {
            return Mono.just(false);
        }
        return tokenRedisTemplate.opsForValue()
                .set(key, identity, Duration.ofSeconds(ttl))
                .doOnNext(ok -> log.debug("L2 cache stored key={} ttl={}s", key, ttl));
    }

    public Mono<Boolean> invalidate(String key) {
        return tokenRedisTemplate.delete(key).map(count -> count > 0);
    }

    /** Mark a token as revoked — checked during cold-path JWT validation. */
    public Mono<Boolean> revoke(String tokenKey, long ttlSeconds) {
        String revKey = REVOCATION_PREFIX + tokenKey;
        return stringTemplate.opsForValue()
                .set(revKey, "1", Duration.ofSeconds(ttlSeconds))
                .doOnNext(ok -> log.info("Token revoked, key={}", tokenKey));
    }

    public Mono<Boolean> isRevoked(String tokenKey) {
        return stringTemplate.hasKey(REVOCATION_PREFIX + tokenKey);
    }
}
