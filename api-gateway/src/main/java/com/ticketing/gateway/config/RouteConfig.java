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
 *  - Paths are forwarded as-is (no stripping) — all service controllers
 *    include the /api prefix in their @RequestMapping.
 *  - Admin routes (/api/admin/**) are guarded at the AuthFilter level
 *    (requires ADMIN role); routing here merely selects the upstream.
 */
@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // ── Auth service — no retries (login/token ops must not duplicate) ──
                // stripPrefix(1) strips /api → auth-service controller is at /auth (no /api prefix)
                .route("auth-service", r -> r
                        .path("/api/auth/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                        )
                        .uri("http://auth-service:8081"))

                // ── Admin: Auth service (users) ───────────────────────────────────
                .route("admin-auth-service", r -> r
                        .path("/api/admin/users/**", "/api/admin/users")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                        )
                        .uri("http://auth-service:8081"))

                // ── Ticket service ────────────────────────────────────────────────
                .route("ticket-service", r -> r
                        .path("/api/tickets/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                                .retry(config -> config
                                        .setRetries(2)
                                        .setStatuses(
                                            org.springframework.http.HttpStatus.BAD_GATEWAY,
                                            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                                        ))
                        )
                        .uri("http://ticket-service:8082"))

                // ── Admin: Ticket service (events + tickets) ──────────────────────
                .route("admin-ticket-service", r -> r
                        .path("/api/admin/events/**", "/api/admin/events",
                              "/api/admin/tickets/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                        )
                        .uri("http://ticket-service:8082"))

                // ── Order service ─────────────────────────────────────────────────
                .route("order-service", r -> r
                        .path("/api/orders/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://order-service:8083"))

                // ── Admin: Order service ──────────────────────────────────────────
                .route("admin-order-service", r -> r
                        .path("/api/admin/orders/**", "/api/admin/orders")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://order-service:8083"))

                // ── Saga orchestrator ─────────────────────────────────────────────
                .route("admin-saga-service", r -> r
                        .path("/api/admin/sagas/**", "/api/admin/sagas")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://saga-orchestrator:8084"))

                // ── Pricing service — WebSocket for real-time price push ───────────
                .route("pricing-service-ws", r -> r
                        .path("/api/pricing/stream/**")
                        .filters(f -> f.addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("ws://pricing-service:8085"))

                .route("pricing-service", r -> r
                        .path("/api/pricing/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://pricing-service:8085"))

                // ── Admin: Pricing service ────────────────────────────────────────
                .route("admin-pricing-service", r -> r
                        .path("/api/admin/price-rules/**", "/api/admin/price-rules")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://pricing-service:8085"))

                // ── Reservation service ───────────────────────────────────────────
                .route("reservation-service", r -> r
                        .path("/api/reservations/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://reservation-service:8086"))

                // ── Admin: Reservation service ────────────────────────────────────
                .route("admin-reservation-service", r -> r
                        .path("/api/admin/reservations/**", "/api/admin/reservations")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://reservation-service:8086"))

                // ── Payment service ───────────────────────────────────────────────
                .route("payment-service", r -> r
                        .path("/api/payments/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://payment-service:8087"))

                // ── Admin: Payment service ────────────────────────────────────────
                .route("admin-payment-service", r -> r
                        .path("/api/admin/payments/**", "/api/admin/payments")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://payment-service:8087"))

                // ── Secondary market ──────────────────────────────────────────────
                .route("secondary-market-service", r -> r
                        .path("/api/secondary/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://secondary-market-service:8088"))

                // ── Admin: Secondary market (listings) ────────────────────────────
                .route("admin-secondary-service", r -> r
                        .path("/api/admin/listings/**", "/api/admin/listings")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://secondary-market-service:8088"))

                // ── Admin: Notification service ───────────────────────────────────
                .route("admin-notification-service", r -> r
                        .path("/api/admin/notifications/**", "/api/admin/notifications")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway"))
                        .uri("http://notification-service:8089"))

                .build();
    }
}
