package com.ticketing.pricing.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    public static final String PRICE_RULES_CACHE  = "priceRules";
    /** facePrice per ticketId — long TTL since facePrice rarely changes. */
    public static final String FACE_PRICE_CACHE   = "ticketFacePrices";

    @Bean
    public CacheManager cacheManager() {
        var manager = new CaffeineCacheManager();
        manager.registerCustomCache(PRICE_RULES_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .maximumSize(1000)
                        .recordStats()
                        .build());
        manager.registerCustomCache(FACE_PRICE_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .recordStats()
                        .build());
        return manager;
    }
}
