package com.ticketing.gateway.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Resolved identity stored in L1 (in-process) and L2 (Redis) caches.
 * Produced by JWT validation; consumed by the identity-injection filter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenIdentity implements Serializable {

    private String userId;
    private String role;
    private String tenantId;
    private Instant expiresAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** Remaining TTL in seconds — used when writing to Redis. */
    public long remainingTtlSeconds() {
        long ttl = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(ttl, 0);
    }
}
