package com.ticketing.pricing.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ticketing.pricing.dto.response.PriceRuleResponse;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    public static final String PRICE_RULES_CACHE = "priceRules";

    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(1000)
                .recordStats();
    }

    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        var manager = new CaffeineCacheManager(PRICE_RULES_CACHE);
        manager.setCaffeine(caffeine);
        return manager;
    }

    /**
     * SSE emitter registry: eventId -> list of active SseEmitter instances.
     * Exposed as a bean so both PricingService and PricingController share it.
     */
    @Bean
    public ConcurrentHashMap<String, List<SseEmitter>> sseEmitterRegistry() {
        return new ConcurrentHashMap<>();
    }
}
