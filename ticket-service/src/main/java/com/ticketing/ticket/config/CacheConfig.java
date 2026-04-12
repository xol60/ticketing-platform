package com.ticketing.ticket.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * L1 cache configuration using Caffeine (in-process).
 *
 * Cache regions:
 *  - "tickets"       : single ticket by id       TTL=30s  max=2000
 *  - "tickets-event" : all tickets for an event  TTL=30s  max=500
 *
 * Short TTL because ticket status changes frequently (reserve/confirm/release).
 * Explicit eviction on every write keeps L1 consistent with DB.
 *
 * L2 (Redis) is managed manually in TicketService for cross-instance consistency.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        var ticketsCache = new CaffeineCache("tickets",
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .maximumSize(2000)
                        .recordStats()
                        .build());

        var ticketsEventCache = new CaffeineCache("tickets-event",
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .maximumSize(500)
                        .recordStats()
                        .build());

        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(ticketsCache, ticketsEventCache));
        return manager;
    }
}
