package com.ticketing.gateway.cache;

import com.ticketing.gateway.security.JwtService;
import com.ticketing.gateway.security.TokenIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Orchestrates the two-layer token resolution pipeline:
 *
 *   L1 (in-process LRU)
 *     ↓ miss
 *   L2 (Redis)
 *     ↓ miss
 *   Cold path (full JWT validation + revocation check)
 *     ↓ valid
 *   Write-back to L2 then L1
 *     ↓
 *   Generation check — compare identity.tokenGeneration against Redis token_gen:{userId}
 *   (L1 gen cache TTL 5 s to bound staleness after a security event)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCacheService {

    private final L1TokenCache  l1;
    private final L2TokenCache  l2;
    private final JwtService    jwtService;
    private final TokenGenCache tokenGenCache;

    /**
     * Resolves a raw bearer token to a TokenIdentity.
     * Returns empty Mono if the token is invalid, revoked, or its generation is stale.
     */
    public Mono<TokenIdentity> resolve(String rawToken) {
        String cacheKey = jwtService.cacheKey(rawToken);

        // ── Step 1: L1 hit ───────────────────────────────────────────────────
        return Mono.justOrEmpty(l1.get(cacheKey))
                .switchIfEmpty(

                    // ── Step 2: L2 hit ────────────────────────────────────────
                    l2.get(cacheKey)
                      .flatMap(identity -> {
                          l1.put(cacheKey, identity); // write-back to L1
                          return Mono.just(identity);
                      })
                      .switchIfEmpty(

                          // ── Step 3: Cold path — full JWT validation ───────────
                          coldValidate(rawToken, cacheKey)
                      )
                )
                // ── Step 4: Generation check (applies to cache hits AND cold path) ──
                .flatMap(identity -> validateGeneration(identity, cacheKey));
    }

    private Mono<TokenIdentity> coldValidate(String rawToken, String cacheKey) {
        return Mono.fromCallable(() -> jwtService.validate(rawToken))
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        log.debug("Cold-path JWT validation failed");
                        return Mono.empty();
                    }
                    TokenIdentity identity = opt.get();
                    return checkRevocation(cacheKey, identity);
                });
    }

    private Mono<TokenIdentity> checkRevocation(String cacheKey, TokenIdentity identity) {
        return l2.isRevoked(cacheKey)
                .flatMap(revoked -> {
                    if (revoked) {
                        log.warn("Revoked token presented, userId={}", identity.getUserId());
                        return Mono.<TokenIdentity>empty();
                    }
                    // Write-back to both cache layers
                    return l2.put(cacheKey, identity)
                             .doOnNext(ok -> l1.put(cacheKey, identity))
                             .thenReturn(identity);
                });
    }

    /** Called on logout — invalidates both cache layers and marks token revoked. */
    public Mono<Void> revoke(String rawToken) {
        String cacheKey = jwtService.cacheKey(rawToken);
        l1.invalidate(cacheKey);
        return l2.revoke(cacheKey, 86400L) // keep revocation record for 24 h
                 .then(l2.invalidate(cacheKey))
                 .then();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Validates the token generation claim against the current counter in Redis.
     *
     * If the counters match → pass the identity through unchanged.
     * If they diverge (counter was incremented by a security event) → evict the
     * stale entry from both cache layers and return empty (→ 401 at AuthFilter).
     */
    private Mono<TokenIdentity> validateGeneration(TokenIdentity identity, String cacheKey) {
        return tokenGenCache.getGeneration(identity.getUserId())
                .flatMap(currentGen -> {
                    if (identity.getTokenGeneration() == currentGen) {
                        return Mono.just(identity);
                    }
                    log.warn("Token generation mismatch — possible stolen token. " +
                                    "userId={} tokenGen={} currentGen={}",
                            identity.getUserId(), identity.getTokenGeneration(), currentGen);
                    // Evict stale entry so it is not served again from any layer
                    l1.invalidate(cacheKey);
                    tokenGenCache.invalidate(identity.getUserId());
                    return l2.invalidate(cacheKey).then(Mono.empty());
                });
    }
}
