package com.ticketing.gateway.config;

import lombok.AllArgsConstructor;
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
    /**
     * Paths that bypass auth for ALL HTTP methods (auth flow, health probes).
     * Match is {@code path.startsWith(prefix)}.
     */
    private List<String> publicPaths = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/actuator/health",
            // The agent turn is a POST with a body, so publicGetPaths cannot
            // cover it. Public for the same reason /api/auth/register is: the
            // funnel exists to collect a signal from someone who has not
            // committed to anything, and a login wall on the first message
            // loses exactly those people. The endpoint is read-only and
            // returns event ids; identity is required later, at checkout.
            "/api/agent/"
    );

    /**
     * Paths that bypass auth for GET requests only — public read-only browse
     * endpoints. Catalogue-style data (events, tickets, search results,
     * secondary-market listings, public price rules) is safe to expose without
     * a token; the corresponding POST/PATCH/DELETE on the same prefix still
     * requires auth (and possibly ADMIN role).
     *
     * <p>Match is {@code path.startsWith(prefix)} AND {@code method == GET}.
     */
    private List<String> publicGetPaths = List.of(
            "/api/tickets/events",      // GET list + GET /{id}/tickets (Phase 3b)
            "/api/tickets",             // GET ticket-by-id and event-scoped listings
            "/api/search/",             // multi-field search + autocomplete
            "/api/secondary/listings",  // browse the resale market
            "/api/pricing/rules"        // current surge rule for an event (read-only)
    );

    /**
     * Role-based access rules, evaluated in order after authentication. The
     * FIRST rule whose {@code pathPrefix} matches ({@code startsWith}) AND whose
     * {@code methods} contains the request method (empty = all methods) decides
     * the required roles: if the caller's role is not in {@code roles}, the
     * gateway returns 403. Order matters — put more specific prefixes first.
     *
     * <p>ADMIN is included in every rule so admins are never blocked. Public GET
     * browse traffic is already short-circuited by {@link #publicGetPaths} before
     * these rules run, so the write-verb rules below only ever see mutating
     * requests.
     */
    private List<RoleRule> roleRules = List.of(
            new RoleRule(List.of(), "/api/admin/", List.of("ADMIN")),
            new RoleRule(List.of("POST", "PUT", "PATCH", "DELETE"),
                    "/api/pricing/rules", List.of("EVENT_OWNER", "ADMIN")),
            new RoleRule(List.of("POST", "PUT", "PATCH", "DELETE"),
                    "/api/tickets", List.of("EVENT_OWNER", "ADMIN"))
    );

    /**
     * A single method+path→roles authorization rule. {@code methods} empty means
     * the rule applies to every HTTP method.
     */
    @Data
    @AllArgsConstructor
    public static class RoleRule {
        private List<String> methods = List.of();
        private String       pathPrefix;
        private List<String> roles   = List.of();

        /** True if this rule governs the given request. */
        public boolean matches(String path, String method) {
            if (pathPrefix == null || !path.startsWith(pathPrefix)) {
                return false;
            }
            return methods == null || methods.isEmpty() || methods.contains(method);
        }

        /** True if a caller holding {@code role} is permitted by this rule. */
        public boolean allows(String role) {
            return roles != null && roles.contains(role);
        }
    }

    @Data
    public static class Jwt {
        private String secret = "changeme";
        private long accessTokenExpirySeconds  = 900;   // 15 min
        private long refreshTokenExpirySeconds = 604800; // 7 days
    }

    @Data
    public static class RateLimit {
        /**
         * Master on/off switch. When false, {@code RateLimitFilter} short-circuits
         * and every request passes. Bound from {@code GATEWAY_RATE_LIMIT_ENABLED}
         * so it can be toggled with a restart (no rebuild). Temporarily disabled
         * for bulk seeding / dev; re-enable before production.
         */
        private boolean enabled = true;
        // requests per second per key (IP:userId)
        private int  requestsPerSecond = 20;
        private int  burstCapacity     = 40;
        private long windowSeconds     = 60;
        // per-path overrides: path prefix → requests/sec
        private Map<String, Integer> pathOverrides = Map.of(
                "/api/auth", 5,
                "/api/agent", 2
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

        /**
         * Per-service override for {@link #slowCallDurationSeconds}.
         *
         * <p>"Slow" is not a property of the gateway, it is a property of what
         * the call does. Three seconds is right for a database read and
         * meaningless for a language-model turn, which takes tens of seconds
         * when everything is working perfectly. With one global value the agent
         * marked every single call slow, crossed the 80% slow-call rate within
         * one sliding window, and opened a circuit — reporting an outage that
         * consisted entirely of the service doing its job.
         */
        private Map<String, Long> slowCallDurationSecondsByService = Map.of(
                "agent-service", 180L
        );
    }

    @Data
    public static class Cache {
        private long l1TtlSeconds  = 30;   // in-process LRU TTL
        private int  l1MaxSize     = 5000; // max entries in L1
        private long l2TtlSeconds  = 300;  // Redis TTL fallback (actual = token exp)
    }
}
