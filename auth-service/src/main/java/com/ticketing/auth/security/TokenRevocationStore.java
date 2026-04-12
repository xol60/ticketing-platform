package com.ticketing.auth.security;

import com.ticketing.auth.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Stores revoked access token hashes in Redis.
 *
 * On logout the access token hash is written here with TTL = token remaining lifetime.
 * The API Gateway reads from this same Redis key space when doing cold-path validation.
 *
 * Key format: "revoked:token:{hash}"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationStore {

    private static final String PREFIX = "revoked:token:";

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties      properties;

    public void revoke(String tokenHash, long ttlSeconds) {
        if (ttlSeconds <= 0) return;
        String key = PREFIX + tokenHash;
        redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
        log.info("Access token revoked, hash={}", tokenHash.substring(0, 12) + "...");
    }

    public boolean isRevoked(String tokenHash) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + tokenHash));
    }
}
