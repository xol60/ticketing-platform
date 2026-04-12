package com.ticketing.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines all downstream routes.
 *
 * Routing strategy:
 *  - Path prefix → service container name (Docker internal DNS)
 *  - StripPrefix=1 removes the /api segment before forwarding
 *  - Each route has its own timeout via request header override
 */
@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // Auth service — no retries (login/token ops must not be duplicated)
                .route("auth-service", r -> r
                        .path("/api/auth/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                        )
                        .uri("http://auth-service:8081"))

                // Ticket service
                .route("ticket-service", r -> r
                        .path("/api/tickets/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                                .retry(config -> config
                                        .setRetries(2)
                                        .setStatuses(
                                            org.springframework.http.HttpStatus.BAD_GATEWAY,
                                            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                                        ))
                        )
                        .uri("http://ticket-service:8082"))

                // Order service
                .route("order-service", r -> r
                        .path("/api/orders/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://order-service:8083"))

                // Pricing service — WebSocket for real-time price push
                .route("pricing-service-ws", r -> r
                        .path("/api/pricing/stream/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("ws://pricing-service:8085"))

                .route("pricing-service", r -> r
                        .path("/api/pricing/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://pricing-service:8085"))

                // Reservation service
                .route("reservation-service", r -> r
                        .path("/api/reservations/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://reservation-service:8086"))

                // Payment service
                .route("payment-service", r -> r
                        .path("/api/payments/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://payment-service:8087"))

                // Secondary market
                .route("secondary-market-service", r -> r
                        .path("/api/secondary/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://secondary-market-service:8088"))

                .build();
    }
}
