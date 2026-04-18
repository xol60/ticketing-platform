package com.ticketing.order.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active SSE connections keyed by orderId.
 *
 * Lifecycle:
 *   1. Client opens GET /api/orders/{id}/stream  → register(orderId)
 *   2. Saga pushes price-changed / confirmed / failed → push(orderId, ...)
 *   3. On terminal event: emitter.complete() → auto-removed from map
 *   4. On timeout (7 min) or client disconnect → auto-removed
 */
@Slf4j
@Component
public class OrderSseRegistry {

    /** Slightly longer than the saga's 6-minute price-confirm window. */
    private static final long SSE_TIMEOUT_MS = Duration.ofMinutes(7).toMillis();

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers a new SSE emitter for the given orderId.
     * Any previous stale emitter for the same order is completed first.
     */
    public SseEmitter register(String orderId) {
        SseEmitter stale = emitters.remove(orderId);
        if (stale != null) {
            try { stale.complete(); } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(orderId, emitter);

        emitter.onCompletion(() -> emitters.remove(orderId));
        emitter.onTimeout(() -> {
            log.debug("SSE timeout orderId={}", orderId);
            emitters.remove(orderId);
        });
        emitter.onError(e -> {
            log.debug("SSE error orderId={}: {}", orderId, e.getMessage());
            emitters.remove(orderId);
        });

        log.debug("SSE registered orderId={} active={}", orderId, emitters.size());
        return emitter;
    }

    /**
     * Pushes a named SSE event to the user waiting on this order.
     * No-op if no client is connected.
     */
    public void push(String orderId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(orderId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            log.debug("SSE pushed orderId={} event={}", orderId, eventName);
        } catch (Exception e) {
            log.warn("SSE push failed orderId={} event={}: {}", orderId, eventName, e.getMessage());
            emitters.remove(orderId);
        }
    }

    /**
     * Pushes a final event and closes the stream.
     * Called when the saga reaches a terminal state (confirmed / failed / cancelled).
     */
    public void complete(String orderId, String eventName, Object data) {
        SseEmitter emitter = emitters.remove(orderId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            emitter.complete();
            log.debug("SSE completed orderId={} event={}", orderId, eventName);
        } catch (Exception e) {
            log.warn("SSE complete failed orderId={}: {}", orderId, e.getMessage());
        }
    }
}
