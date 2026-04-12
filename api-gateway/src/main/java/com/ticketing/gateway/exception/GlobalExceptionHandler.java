package com.ticketing.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import com.ticketing.gateway.filter.TraceIdFilter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Order(-1) // before DefaultErrorWebExceptionHandler
@Component
@RequiredArgsConstructor
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String traceId = (String) exchange.getAttributes()
                .getOrDefault(TraceIdFilter.TRACE_ID_ATTR, "unknown");

        HttpStatus status = resolveStatus(ex);

        log.error("Unhandled gateway error trace={} status={}: {}",
                traceId, status, ex.getMessage(), ex);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status",  status.value());
        body.put("message", userFacingMessage(ex, status));
        body.put("traceId", traceId);

        try {
            byte[]     bytes  = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception writeEx) {
            log.error("Failed to write error response", writeEx);
            return exchange.getResponse().setComplete();
        }
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return HttpStatus.valueOf(rse.getStatusCode().value());
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String userFacingMessage(Throwable ex, HttpStatus status) {
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return "An unexpected error occurred";
        }
        return Optional.ofNullable(ex.getMessage()).orElse("Error");
    }
}
