package com.ticketing.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains an outbox table by retrying Kafka publishes for each unpublished row.
 *
 * <p>The actual scheduler annotation ({@code @Scheduled(fixedDelay = ...)}) lives
 * in each service's concrete drain component — keeps the common-lib free of
 * service-specific Spring configuration concerns. This class implements the loop
 * body and is reusable across services.
 */
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);
    private static final long PUBLISH_TIMEOUT_SECONDS = 2;
    private static final int MAX_RETRIES = 10;

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxEntryFinder finder;
    private final OutboxEntryUpdater updater;

    public OutboxDrainer(KafkaTemplate<String, DomainEvent> kafkaTemplate,
                         ObjectMapper objectMapper,
                         OutboxEntryFinder finder,
                         OutboxEntryUpdater updater) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper  = objectMapper;
        this.finder        = finder;
        this.updater       = updater;
    }

    /**
     * Run one drain pass. Designed to be called by a {@code @Scheduled} method
     * in each service. Idempotent; multiple instances can run concurrently but
     * will simply race for the same rows — a winner wins.
     */
    public void drainOnce(int batchSize) {
        List<? extends OutboxEntry> batch = finder.findUnpublishedBatch(batchSize);
        if (batch.isEmpty()) return;

        log.info("Draining outbox: {} unpublished event(s)", batch.size());
        for (OutboxEntry entry : batch) {
            if (entry.getRetryCount() >= MAX_RETRIES) {
                log.error("Outbox entry exceeded max retries id={} type={} retries={} — leaving for manual intervention",
                        entry.getId(), entry.getEventType(), entry.getRetryCount());
                continue;
            }
            attemptRepublish(entry);
        }
    }

    private void attemptRepublish(OutboxEntry entry) {
        try {
            DomainEvent event = (DomainEvent) objectMapper.readValue(
                    entry.getPayload(),
                    Class.forName("com.ticketing.common.events." + entry.getEventType()));

            kafkaTemplate.send(entry.getTopic(), entry.getMessageKey(), event)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            entry.markPublished();
            updater.save(entry);
            log.info("Drained outbox event id={} type={} on retry {}",
                    entry.getId(), entry.getEventType(), entry.getRetryCount());
        } catch (Exception e) {
            entry.recordRetry(e.getMessage());
            updater.save(entry);
            log.warn("Outbox retry failed id={} type={} attempt={}: {}",
                    entry.getId(), entry.getEventType(), entry.getRetryCount(), e.getMessage());
        }
    }

    @FunctionalInterface
    public interface OutboxEntryFinder {
        List<? extends OutboxEntry> findUnpublishedBatch(int batchSize);
    }

    @FunctionalInterface
    public interface OutboxEntryUpdater {
        void save(OutboxEntry entry);
    }
}
