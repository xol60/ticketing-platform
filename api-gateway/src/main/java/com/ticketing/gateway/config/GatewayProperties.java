package com.ticketing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Jwt jwt = new Jwt();
    private RateLimit rateLimit = new RateLimit();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Cache cache = new Cache();
    private List<String> publicPaths = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/actuator/health"
    );

    @Data
    public static class Jwt {
        private String secret = "changeme";
        private long accessTokenExpirySeconds  = 900;   // 15 min
        private long refreshTokenExpirySeconds = 604800; // 7 days
    }

    @Data
    public static class RateLimit {
        // requests per second per key (IP:userId)
        private int  requestsPerSecond = 20;
        private int  burstCapacity     = 40;
        private long windowSeconds     = 60;
        // per-path overrides: path prefix → requests/sec
        private Map<String, Integer> pathOverrides = Map.of(
                "/api/auth", 5
        );
    }

    @Data
    public static class CircuitBreaker {
        private int    failureRateThreshold      = 50;   // % failures to open
        private int    slowCallRateThreshold      = 80;   // % slow calls to open
        private long   slowCallDurationSeconds    = 3;
        private int    permittedCallsInHalfOpen   = 3;
        private long   waitDurationInOpenSeconds  = 30;
        private int    slidingWindowSize          = 20;
    }

    @Data
    public static class Cache {
        private long l1TtlSeconds  = 30;   // in-process LRU TTL
        private int  l1MaxSize     = 5000; // max entries in L1
        private long l2TtlSeconds  = 300;  // Redis TTL fallback (actual = token exp)
    }
}
