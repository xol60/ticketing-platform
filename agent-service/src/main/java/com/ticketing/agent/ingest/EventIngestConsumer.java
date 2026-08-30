package com.ticketing.agent.ingest;

import com.ticketing.agent.llm.OllamaClient;
import com.ticketing.common.events.EventSearchIndexedEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code event.search.indexed} and drives ingestion.
 *
 * <p>The second consumer on that topic. search-service projects the same
 * messages into Elasticsearch for keyword search; this one projects them into
 * Postgres and pgvector for semantic matching. Neither knows the other exists,
 * and neither is affected by the other falling behind.
 *
 * <h3>Two kinds of failure, two answers</h3>
 * An earlier version acked unconditionally, reasoning that one unparseable
 * event should not stall a single-threaded partition for a minute per retry.
 * That reasoning was sound for the failure it imagined and catastrophic for
 * the one that happened: when Postgres went down, <em>every</em> event failed,
 * and the consumer cheerfully acked sixty-five messages into oblivion. Kafka
 * retention was the only copy.
 *
 * <p>So failures are now split by whether retrying could ever help:
 *
 * <ul>
 *   <li><b>Infrastructure down</b> — Postgres unreachable, Ollama not
 *       answering. Nothing about this message caused it and every other
 *       message will fail the same way, so do <em>not</em> ack. Redelivery is
 *       what makes the backlog survive the outage, and a stalled partition
 *       during a database outage costs nothing that is not already lost.</li>
 *   <li><b>This event is the problem</b> — description the model cannot
 *       produce valid output for, malformed payload. Retrying reproduces it
 *       forever at a minute per attempt, so ack and move on. The event keeps
 *       its projection row with {@code searchable = false}, which puts it in
 *       the review queue where a person can see it.</li>
 * </ul>
 *
 * <p>The default for an unrecognised exception is to ack, because an unknown
 * failure is more likely to be about this event than about the world — but the
 * two cases above cover what actually happens in practice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventIngestConsumer {

    private final IngestionService ingestionService;

    @KafkaListener(
            topics    = Topics.EVENT_SEARCH_INDEXED,
            groupId   = "agent-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEvent(EventSearchIndexedEvent event, Acknowledgment ack) {
        if (event == null || event.getEventId() == null) {
            log.warn("Skipping malformed EventSearchIndexedEvent (null payload or eventId)");
            ack.acknowledge();
            return;
        }

        try {
            ingestionService.ingest(event);
            ack.acknowledge();

        } catch (DataAccessException | OllamaClient.OllamaException e) {
            // The world is broken, not this message. Leaving the offset
            // uncommitted is the only thing keeping the backlog alive — Kafka
            // retention is the sole copy of it.
            log.error("Infrastructure failure ingesting event {} — NOT acking, will retry: {}",
                    event.getEventId(), e.getMessage());
            throw e;

        } catch (Exception e) {
            // This event cannot be processed. Retrying reproduces it forever
            // at a minute per attempt and stalls every event behind it.
            log.error("Permanent failure ingesting event {} ({}) — acking to unblock partition: {}",
                    event.getEventId(), event.getName(), e.getMessage(), e);
            ack.acknowledge();
        }
    }
}
