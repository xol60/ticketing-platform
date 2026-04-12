package com.ticketing.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ticketing.gateway.security.TokenIdentity;

@Configuration
public class RedisConfig {

    // Connection factory comes from Spring Boot auto-config (reads spring.data.redis.*)
    // — no need to define our own LettuceConnectionFactory here.

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    @Bean
    public ReactiveRedisTemplate<String, TokenIdentity> tokenRedisTemplate(
            ReactiveRedisConnectionFactory factory) {

        var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        var valueSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, TokenIdentity.class);
        var keySerializer   = new StringRedisSerializer();

        var context = RedisSerializationContext
                .<String, TokenIdentity>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public ObjectMapper objectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
