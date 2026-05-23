package com.ticketing.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ticketing.common.events.DomainEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring for search-service.
 *
 * <p>Consumes only — search-service is a read-only derived-index service that
 * never publishes domain events. We register a deserializer for
 * {@link DomainEvent} so the inbound payload on {@code event.search.indexed}
 * can be cast to {@code EventSearchIndexedEvent} by the listener.
 *
 * <p>Mirrors ticket-service's KafkaConfig deliberately:
 * <ul>
 *   <li><b>MANUAL_IMMEDIATE</b> ack mode — the consumer only acks after a
 *       successful ES upsert/delete. If ES is down, redelivery happens.</li>
 *   <li><b>concurrency = 3</b> — matches the partition count for
 *       {@code event.search.indexed}. Events are keyed by {@code eventId} so
 *       updates for the same event are serialized within a partition,
 *       preventing a stale upsert from overtaking a later delete.</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ObjectMapper kafkaObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public ConsumerFactory<String, DomainEvent> consumerFactory() {
        var deserializer = new JsonDeserializer<>(DomainEvent.class, kafkaObjectMapper());
        deserializer.addTrustedPackages("com.ticketing.*");
        deserializer.setUseTypeMapperForKey(false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "search-service");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> kafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DomainEvent>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
