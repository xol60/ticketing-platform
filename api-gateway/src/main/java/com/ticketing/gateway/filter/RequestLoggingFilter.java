package com.ticketing.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Filter order 5 — request/response logging.
 * Runs after auth so userId is available in the log line.
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startMs = Instant.now().toEpochMilli();

        String method  = exchange.getRequest().getMethod().name();
        String path    = exchange.getRequest().getPath().value();
        String traceId = (String) exchange.getAttributes().get(TraceIdFilter.TRACE_ID_ATTR);
        String userId  = exchange.getRequest().getHeaders().getFirst("X-User-Id");

        return chain.filter(exchange)
                .doFinally(signal -> {
                    long durationMs = Instant.now().toEpochMilli() - startMs;
                    int  status     = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 0;

                    log.info("method={} path={} status={} duration={}ms userId={} trace={}",
                            method, path, status, durationMs,
                            userId != null ? userId : "anon",
                            traceId);
                });
    }
}
