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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCacheService {

    private final L1TokenCache  l1;
    private final L2TokenCache  l2;
    private final JwtService    jwtService;

    /**
     * Resolves a raw bearer token to a TokenIdentity.
     * Returns empty Mono if the token is invalid or revoked.
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
                );
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
}
