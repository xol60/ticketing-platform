package com.ticketing.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Pulls {@code X-Trace-Id} (set by the api-gateway's {@code TraceIdFilter}) off the
 * incoming request and pushes it into SLF4J MDC under key {@code traceId}, so
 * every {@code %X{traceId}} pattern in {@code logging.pattern.console} resolves
 * for the lifetime of the request.
 *
 * <h2>Why a filter, not Sleuth / Micrometer-Tracing</h2>
 * The platform's trace identity is already minted at the edge and propagated by
 * header — every downstream HTTP client and Kafka publisher carries it. We just
 * need to bridge header → MDC inside each servlet service so log aggregation
 * (Loki) can pivot by {@code traceId} for cross-service request reconstruction.
 *
 * <h2>Direct hits (no gateway)</h2>
 * If a request arrives without the header (test runners, internal smoke checks,
 * pod-to-pod calls that bypass the edge), this filter mints a synthetic UUID
 * rather than logging with an empty traceId — the log line is still pivotable.
 *
 * <h2>Ordering</h2>
 * Runs before {@code IdempotencyFilter} so the idempotency path also gets a
 * traceId in its log lines.
 *
 * <h2>Cleanup</h2>
 * {@code MDC.clear()} in the {@code finally} block prevents the value from
 * leaking onto the next request that reuses the worker thread.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY         = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, traceId);

        // Echo back so curl users / contract tests can correlate without the gateway hop.
        if (response.getHeader(TRACE_ID_HEADER) == null) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
