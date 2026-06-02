package com.ticketing.order.service;

import com.ticketing.order.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Tier-1 fast-fail filter for {@code POST /api/orders}: an in-process Caffeine
 * view of which tickets are currently TAKEN (RESERVED or CONFIRMED).
 *
 * <p><b>Why this exists</b>: under flash-sale conditions hundreds of users
 * click Buy on the same ticket within milliseconds. Without this filter, all
 * of them would create Order rows, fire OrderCreatedEvents, instantiate saga
 * state, and only fail at the saga's own SETNX on {@code ticket:lock:{id}}.
 * The slip-through-then-fail path costs DB inserts, Kafka messages, and saga
 * state for every loser — a lot of garbage to produce one CONFIRMED order.
 * Checking this cache first turns 99 of 100 losers into a 409 response in
 * µs, before any persistent state is touched.
 *
 * <p><b>How it stays consistent</b>: a Kafka consumer
 * ({@code TicketStateConsumer}) subscribes to the existing
 * {@code ticket.reserved} / {@code ticket.confirmed} / {@code ticket.released}
 * topics and calls {@link #markTaken(String)} / {@link #markReleased(String)}
 * here. Cross-pod convergence happens at Kafka latency (~1-2 ms). The TTL
 * (60 s) is the upper safety bound; in practice entries are updated by the
 * Kafka stream long before TTL.
 *
 * <p><b>Multi-instance honesty</b>: each pod has its own Caffeine. Worst case
 * is a ms-scale window where Pod B hasn't yet consumed the {@code ticket.reserved}
 * event Pod A's saga produced — Pod B may accept an order that the saga's
 * SETNX then rejects. That's the "acceptable garbage" trade-off explicitly
 * chosen during design; correctness (no overselling) is still guaranteed by
 * the downstream SETNX + {@code @Version}.
 *
 * <p>Cold-event behaviour: a ticket whose status was never broadcast (rare
 * event, never reserved) won't be in the cache. {@link #isTaken(String)}
 * returns {@code false} → request falls through to the next tier (Redis
 * intent-lock) and then the existing saga path. No bias against cold paths.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStatusCache {

    private final CacheManager cacheManager;

    /**
     * Returns {@code true} only if this pod has a recent record that the
     * ticket is RESERVED or CONFIRMED. A {@code false} answer means "not
     * known to be taken" — the caller should still verify downstream.
     */
    public boolean isTaken(String ticketId) {
        Cache cache = cacheManager.getCache(CacheConfig.ORDER_TICKET_STATUS_REGION);
        if (cache == null) return false;
        Cache.ValueWrapper v = cache.get(ticketId);
        return v != null && Boolean.TRUE.equals(v.get());
    }

    /** Mark the ticket as TAKEN. Called by the Kafka consumer on reserved/confirmed. */
    public void markTaken(String ticketId) {
        Cache cache = cacheManager.getCache(CacheConfig.ORDER_TICKET_STATUS_REGION);
        if (cache != null) cache.put(ticketId, Boolean.TRUE);
    }

    /** Invalidate the cache entry for this ticket. Called on ticket.released. */
    public void markReleased(String ticketId) {
        Cache cache = cacheManager.getCache(CacheConfig.ORDER_TICKET_STATUS_REGION);
        if (cache != null) cache.evict(ticketId);
    }
}
