package com.ticketing.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.gateway.ratelimit.SlidingWindowRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

/**
 * Filter order 2 — rate limiter.
 *
 * Rate limit key = IP + ":" + userId (or just IP for unauthenticated requests).
 * Authenticated users get a higher limit via per-user bucketing.
 * Returns 429 with JSON body on breach, including the traceId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final SlidingWindowRateLimiter rateLimiter;
    private final ObjectMapper     objectMapper;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // just after TraceIdFilter
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ip      = resolveIp(exchange);
        String userId  = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        String path    = exchange.getRequest().getPath().value();
        String traceId = (String) exchange.getAttributes().get(TraceIdFilter.TRACE_ID_ATTR);

        // Bucket: authenticated users get their own bucket (higher limit),
        // anonymous requests share an IP-only bucket (lower limit)
        String windowKey = (userId != null && !userId.isBlank())
                ? ip + ":" + userId
                : ip;

        return rateLimiter.isAllowed(windowKey, path)
                .flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(exchange);
                    }
                    log.warn("Rate limit exceeded for key={} path={} trace={}",
                            windowKey, path, traceId);
                    return rejectWith429(exchange, traceId);
                });
    }

    private Mono<Void> rejectWith429(ServerWebExchange exchange, String traceId) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "success",   false,
                    "message",   "Rate limit exceeded — please slow down",
                    "traceId",   Optional.ofNullable(traceId).orElse(""),
                    "retryAfterSeconds", 60
            ));
            DataBuffer buffer = exchange.getResponse()
                    .bufferFactory().wrap(body);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    private String resolveIp(ServerWebExchange exchange) {
        // Honour X-Forwarded-For from Nginx
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getHostString)
                .orElse("unknown");
    }
}
