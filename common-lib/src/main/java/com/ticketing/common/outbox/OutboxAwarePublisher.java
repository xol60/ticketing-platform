package com.ticketing.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Hybrid outbox publisher utility — synchronous Kafka send with outbox fallback.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Call {@code kafkaTemplate.send(...).get(2 s)} — block until broker acks.</li>
 *   <li>On success, return. The happy path adds <b>zero</b> extra DB writes.</li>
 *   <li>On any exception, write a row to the service's {@code outbox_events} table
 *       in its own short transaction. The scheduled drain worker retries later.</li>
 * </ol>
 *
 * <p>This is deliberately <em>not</em> the strict transactional-outbox pattern (which
 * writes every event inside the business transaction). We accept a narrow event-loss
 * window — process crash between business-tx commit and Kafka publish attempt — in
 * exchange for skipping a DB write per published event in the happy path.
 *
 * <p>Each service constructs one of these wired to its own outbox entity subclass
 * and repository. The {@link OutboxEntryFactory} indirection lets the same utility
 * class create the right concrete entity for whichever service is calling.
 */
public class OutboxAwarePublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxAwarePublisher.class);
    private static final long PUBLISH_TIMEOUT_SECONDS = 2;

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final OutboxEntryFactory outboxFactory;
    private final OutboxEntrySaver outboxSaver;

    public OutboxAwarePublisher(KafkaTemplate<String, DomainEvent> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 TransactionTemplate txTemplate,
                                 OutboxEntryFactory outboxFactory,
                                 OutboxEntrySaver outboxSaver) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper  = objectMapper;
        this.txTemplate    = txTemplate;
        this.outboxFactory = outboxFactory;
        this.outboxSaver   = outboxSaver;
    }

    /**
     * Publish synchronously, falling back to the outbox table on failure.
     *
     * @param topic       Kafka topic name
     * @param messageKey  partition key (usually {@code orderId})
     * @param event       the domain event to publish
     */
    public void publish(String topic, String messageKey, DomainEvent event) {
        try {
            kafkaTemplate.send(topic, messageKey, event)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("Published {} to topic={} key={}", event.getClass().getSimpleName(), topic, messageKey);
        } catch (Exception primaryFailure) {
            log.warn("Kafka publish failed, falling back to outbox: topic={} key={} type={} reason={}",
                    topic, messageKey, event.getClass().getSimpleName(), primaryFailure.getMessage());
            persistToOutbox(topic, messageKey, event, primaryFailure.getMessage());
        }
    }

    private void persistToOutbox(String topic, String messageKey, DomainEvent event, String failureReason) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception serializeFailure) {
            // If we can't serialise the event, we can't outbox it either. Log loudly.
            log.error("Outbox persist FAILED — could not serialise event topic={} key={} type={}: {}",
                    topic, messageKey, event.getClass().getSimpleName(), serializeFailure.getMessage());
            return;
        }

        final String finalPayload = payload;
        try {
            txTemplate.executeWithoutResult(status -> {
                OutboxEntry entry = outboxFactory.create();
                entry.setId(UUID.randomUUID());
                entry.setTopic(topic);
                entry.setMessageKey(messageKey);
                entry.setEventType(event.getClass().getSimpleName());
                entry.setPayload(finalPayload);
                entry.setCreatedAt(Instant.now());
                entry.setRetryCount(0);
                entry.setLastError(failureReason);
                outboxSaver.save(entry);
            });
            log.info("Persisted event to outbox: topic={} key={} type={}",
                    topic, messageKey, event.getClass().getSimpleName());
        } catch (Exception outboxFailure) {
            // Both Kafka AND the DB are unavailable. Nothing more we can do here.
            log.error("CATASTROPHIC: outbox persist failed after Kafka failure. Event lost: topic={} key={} type={} kafkaErr={} outboxErr={}",
                    topic, messageKey, event.getClass().getSimpleName(), failureReason, outboxFailure.getMessage());
        }
    }

    /**
     * Factory contract — each service supplies a {@link Supplier} that constructs
     * a fresh concrete subclass of {@link OutboxEntry} ready to be populated.
     */
    @FunctionalInterface
    public interface OutboxEntryFactory {
        OutboxEntry create();
    }

    /**
     * Save contract — each service supplies an adapter around its own JPA repository.
     */
    @FunctionalInterface
    public interface OutboxEntrySaver {
        void save(OutboxEntry entry);
    }
}
