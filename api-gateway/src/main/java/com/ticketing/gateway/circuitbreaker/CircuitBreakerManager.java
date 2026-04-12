package com.ticketing.gateway.circuitbreaker;

import com.ticketing.gateway.config.GatewayProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // Known downstream services — one breaker each
    private static final String[] SERVICES = {
            "auth-service",
            "ticket-service",
            "order-service",
            "saga-orchestrator",
            "pricing-service",
            "reservation-service",
            "payment-service",
            "notification-service"
    };

    @PostConstruct
    public void init() {
        var cbProps = properties.getCircuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbProps.getFailureRateThreshold())
                .slowCallRateThreshold(cbProps.getSlowCallRateThreshold())
                .slowCallDurationThreshold(
                        Duration.ofSeconds(cbProps.getSlowCallDurationSeconds()))
                .permittedNumberOfCallsInHalfOpenState(
                        cbProps.getPermittedCallsInHalfOpen())
                .waitDurationInOpenState(
                        Duration.ofSeconds(cbProps.getWaitDurationInOpenSeconds()))
                .slidingWindowType(
                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbProps.getSlidingWindowSize())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        for (String service : SERVICES) {
            CircuitBreaker cb = registry.circuitBreaker(service);
            cb.getEventPublisher()
              .onStateTransition(event ->
                  log.warn("Circuit breaker [{}] state: {} → {}",
                      service,
                      event.getStateTransition().getFromState(),
                      event.getStateTransition().getToState())
              );
            breakers.put(service, cb);
        }

        log.info("Circuit breakers initialized for {} services", breakers.size());
    }

    /**
     * Returns the CircuitBreaker for the given service name.
     * Resolves the service name from the request path prefix.
     */
    public CircuitBreaker getForPath(String path) {
        String service = resolveService(path);
        return breakers.getOrDefault(service, breakers.get("ticket-service"));
    }

    public Map<String, CircuitBreaker.State> getAllStates() {
        Map<String, CircuitBreaker.State> states = new ConcurrentHashMap<>();
        breakers.forEach((name, cb) -> states.put(name, cb.getState()));
        return states;
    }

    private String resolveService(String path) {
        if (path.startsWith("/api/auth"))          return "auth-service";
        if (path.startsWith("/api/tickets"))       return "ticket-service";
        if (path.startsWith("/api/orders"))        return "order-service";
        if (path.startsWith("/api/pricing"))       return "pricing-service";
        if (path.startsWith("/api/reservations"))  return "reservation-service";
        if (path.startsWith("/api/payments"))      return "payment-service";
        return "ticket-service"; // default fallback
    }
}
