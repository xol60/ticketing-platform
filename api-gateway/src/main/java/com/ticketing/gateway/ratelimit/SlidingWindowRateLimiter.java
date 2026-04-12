package com.ticketing.gateway.ratelimit;

import com.ticketing.gateway.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sliding window rate limiter backed by Redis.
 *
 * Uses a Lua script for atomicity:
 *   - Key: "rl:{windowKey}"
 *   - Removes entries outside the current window
 *   - Counts remaining entries
 *   - Rejects if count >= limit, otherwise adds current timestamp
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimiter {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayProperties           properties;

    // Lua script — atomic sliding window via sorted set
    private static final String SLIDING_WINDOW_SCRIPT = """
            local key        = KEYS[1]
            local now        = tonumber(ARGV[1])
            local window     = tonumber(ARGV[2])
            local limit      = tonumber(ARGV[3])
            local windowStart = now - window * 1000

            redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
            local count = redis.call('ZCARD', key)

            if count >= limit then
                return 0
            end

            redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
            redis.call('PEXPIRE', key, window * 1000)
            return 1
            """;

    private final RedisScript<Long> rateLimitScript =
            RedisScript.of(SLIDING_WINDOW_SCRIPT, Long.class);

    /**
     * @param windowKey identifies the rate limit bucket (e.g. "192.168.1.1:user-123")
     * @param path      request path — used to look up per-path limits
     * @return Mono<true> if request is allowed, Mono<false> if rate limited
     */
    public Mono<Boolean> isAllowed(String windowKey, String path) {
        int  limit         = resolveLimit(path);
        long windowSeconds = properties.getRateLimit().getWindowSeconds();
        long nowMs         = Instant.now().toEpochMilli();
        String redisKey    = "rl:" + windowKey;

        return redisTemplate.execute(
                        rateLimitScript,
                        List.of(redisKey),
                        List.of(String.valueOf(nowMs),
                                String.valueOf(windowSeconds),
                                String.valueOf(limit))
                )
                .next()
                .map(result -> result == 1L)
                .onErrorResume(ex -> {
                    // Redis failure → fail open (allow) to avoid cascading outage
                    log.error("Rate limiter Redis error for key={}: {}", redisKey, ex.getMessage());
                    return Mono.just(true);
                });
    }

    /** Resolve per-path limit or fall back to default requests-per-second. */
    private int resolveLimit(String path) {
        return properties.getRateLimit().getPathOverrides().entrySet().stream()
                .filter(e -> path.startsWith(e.getKey()))
                .mapToInt(e -> e.getValue())
                .findFirst()
                .orElse(properties.getRateLimit().getRequestsPerSecond());
    }
}
