package com.ticketing.gateway.circuitbreaker;

import com.ticketing.gateway.config.GatewayProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Manages one Resilience4j CircuitBreaker per downstream service.
 *
 * Each service has an independent breaker so a slow payment service
 * doesn't open the breaker for ticket or pricing services.
 *
 * State is in-process per pod. For true shared state across pods,
 * integrate with resilience4j-redis (not included here for simplicity,
 * but the service names are the extension point).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerManager {

    private final GatewayProperties properties;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** Breaker for paths no rule claims. Deliberately not a real service. */
    static final String UNMAPPED = "unmapped-route";

    // Known downstream services — one breaker each
    private static final String[] SERVICES = {
            "auth-service",
            "ticket-service",
            "order-service",
            "saga-orchestrator",
            "pricing-service",
            "reservation-service",
            "payment-service",
            "notification-service",
            "secondary-market-service",
            "search-service",
            // Was missing, and unmapped paths fall through to ticket-service —
            // so every agent turn counted against ticket browsing. An agent
            // turn is slow by design, so the shared breaker opened on healthy
            // traffic and blocked a service that was never involved.
            "agent-service",
            // Home for paths that match nothing. A gateway that cannot name the
            // service behind a route must not guess a real one: the guess is
            // invisible, and its cost is borne by whichever service was named.
            UNMAPPED
    };


    @PostConstruct
    public void init() {
        var cbProps = properties.getCircuitBreaker();

        for (String service : SERVICES) {
            breakers.computeIfAbsent(service, n -> buildBreaker(n, cbProps));
        }
        log.info("Circuit breakers initialized for {} services", breakers.size());
    }

    private CircuitBreaker buildBreaker(String service,
                                        GatewayProperties.CircuitBreaker cbProps) {
        long slowSeconds = cbProps.getSlowCallDurationSecondsByService()
                .getOrDefault(service, cbProps.getSlowCallDurationSeconds());

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbProps.getFailureRateThreshold())
                .slowCallRateThreshold(cbProps.getSlowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofSeconds(slowSeconds))
                .permittedNumberOfCallsInHalfOpenState(
                        cbProps.getPermittedCallsInHalfOpen())
                .waitDurationInOpenState(
                        Duration.ofSeconds(cbProps.getWaitDurationInOpenSeconds()))
                .slidingWindowType(
                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbProps.getSlidingWindowSize())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)

                // ── Only count INFRASTRUCTURE failures toward the failure rate ──
                // Resilience4j's default behaviour records EVERY Throwable as a
                // failure. With Spring's WebClient that includes 4xx responses
                // (WebClientResponseException.BadRequest, etc.) which the routing
                // layer can surface as exceptions. Counting those would mean:
                //   - 5+ users in a row submitting invalid orders
                //   - → 4xx exceptions accumulate in the CB sliding window
                //   - → failure rate crosses 50%
                //   - → circuit opens for 30s
                //   - → LEGITIMATE traffic is blocked because users sent bad input
                // That's a self-DOS pattern triggered by client-side mistakes.
                //
                // Note: rate-limit 429s do NOT reach this filter — RateLimitFilter
                // short-circuits the chain. We still list 429 in ignoreExceptions
                // as defence-in-depth in case future filter reordering changes that.
                //
                // We explicitly record only what actually means "service unhealthy":
                //   - Network errors (connection refused/reset, DNS failures)
                //   - Timeouts (downstream too slow to respond)
                //   - 5xx responses (server-side failure)
                .recordExceptions(
                        IOException.class,
                        TimeoutException.class,
                        WebClientResponseException.InternalServerError.class,   // 500
                        WebClientResponseException.BadGateway.class,            // 502
                        WebClientResponseException.ServiceUnavailable.class,    // 503
                        WebClientResponseException.GatewayTimeout.class         // 504
                )
                // Belt-and-suspenders — if any 4xx slips through as an exception
                // (e.g. a custom downstream client wrapping the response), ignore it.
                .ignoreExceptions(
                        WebClientResponseException.BadRequest.class,            // 400
                        WebClientResponseException.Unauthorized.class,          // 401
                        WebClientResponseException.Forbidden.class,             // 403
                        WebClientResponseException.NotFound.class,              // 404
                        WebClientResponseException.MethodNotAllowed.class,      // 405
                        WebClientResponseException.Conflict.class,              // 409
                        WebClientResponseException.UnprocessableEntity.class,   // 422
                        WebClientResponseException.TooManyRequests.class        // 429
                )
                .build();

        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker(service);
        cb.getEventPublisher()
          .onStateTransition(event ->
              log.warn("Circuit breaker [{}] state: {} → {}  (slow-call threshold {}s)",
                  service,
                  event.getStateTransition().getFromState(),
                  event.getStateTransition().getToState(),
                  slowSeconds)
          );
        return cb;
    }

    /**
     * Returns the CircuitBreaker for the given service name.
     * Resolves the service name from the request path prefix.
     */
    public CircuitBreaker getForPath(String path) {
        String service = resolveService(path);
        return breakers.getOrDefault(service, breakers.get(UNMAPPED));
    }

    public Map<String, CircuitBreaker.State> getAllStates() {
        Map<String, CircuitBreaker.State> states = new ConcurrentHashMap<>();
        breakers.forEach((name, cb) -> states.put(name, cb.getState()));
        return states;
    }

    private String resolveService(String path) {
        // ── Regular routes ───────────────────────────────────────────────────
        if (path.startsWith("/api/auth"))          return "auth-service";
        if (path.startsWith("/api/tickets"))       return "ticket-service";
        if (path.startsWith("/api/orders"))        return "order-service";
        if (path.startsWith("/api/pricing"))       return "pricing-service";
        if (path.startsWith("/api/reservations"))  return "reservation-service";
        if (path.startsWith("/api/payments"))      return "payment-service";
        if (path.startsWith("/api/secondary"))     return "secondary-market-service";
        if (path.startsWith("/api/search"))        return "search-service";
        if (path.startsWith("/api/agent"))         return "agent-service";

        // ── Admin routes — map to the owning service ─────────────────────────
        if (path.startsWith("/api/admin/users"))         return "auth-service";
        if (path.startsWith("/api/admin/events"))        return "ticket-service";
        if (path.startsWith("/api/admin/tickets"))       return "ticket-service";
        if (path.startsWith("/api/admin/orders"))        return "order-service";
        if (path.startsWith("/api/admin/payments"))      return "payment-service";
        if (path.startsWith("/api/admin/sagas"))         return "saga-orchestrator";
        if (path.startsWith("/api/admin/price-rules"))   return "pricing-service";
        if (path.startsWith("/api/admin/reservations"))  return "reservation-service";
        if (path.startsWith("/api/admin/listings"))      return "secondary-market-service";
        if (path.startsWith("/api/admin/notifications")) return "notification-service";

        // Not "ticket-service". Naming a real service here is the opposite of
        // conservative — it makes an unrouted path able to open a breaker in
        // front of traffic that has nothing to do with it.
        return UNMAPPED;
    }
}
