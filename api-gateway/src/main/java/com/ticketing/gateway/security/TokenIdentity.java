package com.ticketing.gateway.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Resolved identity stored in L1 (in-process) and L2 (Redis) caches.
 * Produced by JWT validation; consumed by the identity-injection filter.
 *
 * tokenGeneration mirrors the "gen" claim in the JWT and is checked against
 * Redis token_gen:{userId} on every request. When the counter is incremented
 * (e.g. on refresh-token reuse detection) all cached identities with an older
 * generation are rejected immediately without waiting for natural token expiry.
 *
 * @JsonIgnoreProperties(ignoreUnknown=true) guards against deserialization
 * failures when new fields are added to the cached JSON over time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenIdentity implements Serializable {

    private String userId;
    private String role;
    private String tenantId;
    private Instant expiresAt;
    private long    tokenGeneration;

    /** Derived — not persisted to Redis cache; recomputed on each access. */
    @JsonIgnore
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** Remaining TTL in seconds — used when writing to Redis. Not cached. */
    @JsonIgnore
    public long remainingTtlSeconds() {
        long ttl = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(ttl, 0);
    }
}
