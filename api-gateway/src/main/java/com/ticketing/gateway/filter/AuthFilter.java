package com.ticketing.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.gateway.cache.TokenCacheService;
import com.ticketing.gateway.config.GatewayProperties;
import com.ticketing.gateway.security.TokenIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Filter order 4 — authentication.
 *
 * Pipeline:
 *   1. Skip if path is public (no auth required)
 *   2. Extract Bearer token from Authorization header
 *   3. Resolve via TokenCacheService (L1 → L2 → cold JWT validation)
 *   4. Reject with 401 if not resolved
 *   5. Strip original Authorization header
 *   6. Inject X-User-Id, X-User-Role, X-Tenant-Id, X-Trace-Id into forwarded request
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFilter implements GlobalFilter, Ordered {

    private final TokenCacheService tokenCacheService;
    private final GatewayProperties properties;
    private final ObjectMapper      objectMapper;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Skip public paths entirely
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String traceId = (String) exchange.getAttributes()
                .get(TraceIdFilter.TRACE_ID_ATTR);

        // Extract Bearer token
        String authHeader = exchange.getRequest()
                .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Missing or malformed Authorization header, path={}", path);
            return rejectWith401(exchange, traceId, "Missing Bearer token");
        }

        String token = authHeader.substring(7);

        return tokenCacheService.resolve(token)
                .flatMap(identity -> forwardWithIdentity(exchange, chain, identity, traceId, token))
                .switchIfEmpty(
                    Mono.defer(() -> rejectWith401(exchange, traceId, "Invalid or expired token"))
                );
    }

    private Mono<Void> forwardWithIdentity(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           TokenIdentity identity,
                                           String traceId,
                                           String rawToken) {
        log.debug("Auth OK userId={} role={} trace={}",
                identity.getUserId(), identity.getRole(), traceId);

        // Build mutated request: strip JWT, inject trusted headers
        var mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);  // strip JWT
                    headers.set("X-User-Id",   identity.getUserId());
                    headers.set("X-User-Role", identity.getRole());
                    headers.set("X-Tenant-Id",
                            Optional.ofNullable(identity.getTenantId()).orElse("default"));
                    headers.set("X-Trace-Id",
                            Optional.ofNullable(traceId).orElse(""));
                })
                .build();

        Mono<Void> downstream = chain.filter(exchange.mutate().request(mutatedRequest).build());

        // On logout, regardless of whether auth-service succeeds or fails,
        // evict the token from both L1 and L2 cache immediately —
        // prevents a revoked token from remaining alive in cache for up to 30 seconds.
        if (isLogoutPath(exchange.getRequest().getPath().value())) {
            return downstream.then(tokenCacheService.revoke(rawToken))
                    .doOnSuccess(v -> log.info("Cache invalidated on logout userId={}", identity.getUserId()));
        }

        return downstream;
    }

    private boolean isLogoutPath(String path) {
        return path.startsWith("/api/auth/logout");
    }

    private Mono<Void> rejectWith401(ServerWebExchange exchange,
                                     String traceId, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "success", false,
                    "message", reason,
                    "traceId", Optional.ofNullable(traceId).orElse("")
            ));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    private boolean isPublicPath(String path) {
        return properties.getPublicPaths().stream()
                .anyMatch(path::startsWith);
    }
}
