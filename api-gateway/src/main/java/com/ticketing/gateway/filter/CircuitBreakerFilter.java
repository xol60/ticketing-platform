package com.ticketing.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.gateway.circuitbreaker.CircuitBreakerManager;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
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

import java.util.Map;
import java.util.Optional;

/**
 * Filter order 3 — circuit breaker.
 *
 * Wraps the downstream chain with a per-service Resilience4j CircuitBreaker.
 * OPEN state → immediate 503, no downstream call.
 * Records success/failure on each completed downstream call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerFilter implements GlobalFilter, Ordered {

    private final CircuitBreakerManager cbManager;
    private final ObjectMapper          objectMapper;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path    = exchange.getRequest().getPath().value();
        String traceId = (String) exchange.getAttributes().get(TraceIdFilter.TRACE_ID_ATTR);
        CircuitBreaker cb = cbManager.getForPath(path);

        return chain.filter(exchange)
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .onErrorResume(CallNotPermittedException.class, ex -> {
                    log.warn("Circuit OPEN for path={} trace={} cb={}",
                            path, traceId, cb.getName());
                    return rejectWith503(exchange, traceId, cb.getName());
                })
                .onErrorResume(ex -> {
                    // Record other downstream errors as CB failures
                    log.error("Downstream error path={} trace={}: {}",
                            path, traceId, ex.getMessage());
                    return rejectWith502(exchange, traceId);
                });
    }

    private Mono<Void> rejectWith503(ServerWebExchange exchange,
                                     String traceId, String service) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().add("Retry-After", "30");

        return writeJson(exchange, Map.of(
                "success", false,
                "message", "Service temporarily unavailable — circuit open for " + service,
                "traceId", Optional.ofNullable(traceId).orElse("")
        ));
    }

    private Mono<Void> rejectWith502(ServerWebExchange exchange, String traceId) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        return writeJson(exchange, Map.of(
                "success", false,
                "message", "Upstream service error",
                "traceId", Optional.ofNullable(traceId).orElse("")
        ));
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, Object body) {
        try {
            byte[]     bytes  = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }
}
