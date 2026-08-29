package com.ticketing.agent.config;

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
 * Kafka consumer wiring. Consume only — this service publishes nothing.
 *
 * <p>Mirrors search-service's config, because both are read models off the same
 * topic and there is no reason for them to behave differently under
 * redelivery. Two differences are deliberate:
 *
 * <ul>
 *   <li><b>groupId</b> is {@code agent-service}, so offsets are tracked
 *       independently. search-service does not know this consumer exists and
 *       is unaffected by it falling behind.</li>
 *   <li><b>concurrency = 1</b>, not 3. Every message here triggers an LLM call
 *       taking seconds, so throughput is bounded by the model, not by thread
 *       count — and a single thread keeps the ingest rate predictable enough
 *       to reason about cost. Raise this only alongside a rate limiter.</li>
 * </ul>
 *
 * <p>{@code MANUAL_IMMEDIATE} ack: the offset advances only after the row is
 * committed. A crash mid-ingest replays the event, and re-ingesting is safe
 * because the write is an upsert keyed by eventId.
 *
 * <p>{@code max.poll.interval.ms} is raised well above the default five
 * minutes. With a batch of slow LLM calls in flight, the default would have
 * the broker decide this consumer is dead and rebalance the partition away
 * mid-work — which looks exactly like a hang and is miserable to diagnose.
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "agent-service");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Small batches: each record costs an LLM round trip, so a large poll
        // would just sit on records the consumer cannot start for minutes.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> kafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DomainEvent>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
