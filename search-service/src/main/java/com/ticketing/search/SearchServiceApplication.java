package com.ticketing.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * search-service — read-only Elasticsearch-backed search subsystem for events.
 *
 * <p>This service is intentionally <em>decoupled</em> from the order saga.
 * Postgres (in ticket-service) is the source of truth for the {@code Event}
 * domain; ES is a derived index synced over a Kafka topic
 * ({@code event.search.indexed}). If ES is unavailable or out-of-sync, the
 * canonical event-detail page (served by ticket-service) is unaffected — only
 * the search box degrades.
 *
 * <p>Scan base packages cover {@code com.ticketing.search} (this module) and
 * {@code com.ticketing.common} (the shared event DTOs that the Kafka consumer
 * deserialises).
 */
@SpringBootApplication(scanBasePackages = {"com.ticketing.search", "com.ticketing.common"})
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
