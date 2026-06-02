package com.ticketing.order.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * L1 in-process Caffeine cache configuration.
 *
 * <p>Two regions, both 60 s TTL, sharing a single Caffeine builder:
 * <ul>
 *   <li><b>orders</b> — single OrderResponse by orderId. Cached on read.
 *   <li><b>order-ticket-status</b> — per-ticketId "TAKEN" marker, the first tier
 *       of the order fast-fail stack in {@code OrderService.createOrder}.
 *       Populated by {@code TicketStateConsumer} subscribing to
 *       {@code ticket.reserved} / {@code ticket.confirmed} / {@code ticket.released}
 *       so every order-service pod converges on the same view within Kafka
 *       latency (~1-2 ms). W-TinyLFU (Caffeine default) naturally retains hot
 *       tickets and evicts cold ones — no explicit "is this event hot" logic.
 * </ul>
 *
 * <p>10 000 max entries: 5 000-ticket flash sale + headroom; tiny per-entry
 * memory (boolean) so the cost is &lt;1 MB heap even when full.
 * NOTE: @EnableCaching is on OrderServiceApplication, not here.
 */
@Configuration
public class CacheConfig {

    public static final String ORDERS_REGION              = "orders";
    public static final String ORDER_TICKET_STATUS_REGION = "order-ticket-status";

    @Bean
    public CacheManager cacheManager() {
        var manager = new CaffeineCacheManager(ORDERS_REGION, ORDER_TICKET_STATUS_REGION);
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(10_000)
                        .recordStats()
        );
        return manager;
    }
}
