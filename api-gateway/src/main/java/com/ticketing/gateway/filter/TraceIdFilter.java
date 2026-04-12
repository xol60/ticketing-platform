package com.ticketing.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Filter order 1 — runs before everything else.
 *
 * Generates a UUID v4 traceId and:
 *  - Attaches it to the request as X-Trace-Id header
 *  - Attaches it to the response as X-Trace-Id header
 *  - Stores it in exchange attributes for downstream filters
 */
@Slf4j
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_ATTR   = "X-Trace-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // -2147483648
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Honour incoming traceId (e.g. from internal caller) or generate new one
        String traceId = exchange.getRequest().getHeaders()
                .getFirst(TRACE_ID_HEADER);

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        final String finalTraceId = traceId;

        // Store for downstream filters
        exchange.getAttributes().put(TRACE_ID_ATTR, finalTraceId);

        // Mutate request with traceId header
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, finalTraceId)
                .build();

        // Add traceId to response headers too
        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, finalTraceId);

        log.debug("TraceId assigned: {}", finalTraceId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }
}
