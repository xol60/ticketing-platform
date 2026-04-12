package com.ticketing.secondary.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer: HTTP client that calls ticket-service to verify
 * whether an event is currently open for sales before a secondary-market purchase.
 *
 * Behaviour:
 *  - Returns false if ticket-service explicitly says the event is closed.
 *  - Returns true (fail-open) if ticket-service is unreachable — the downstream
 *    saga guard in ticket-service is the final authority.
 */
@Slf4j
@Component
public class EventValidationClient {

    private final RestClient restClient;

    public EventValidationClient(
            @Value("${clients.ticket-service.url:http://ticket-service:8082}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * @param eventId the event whose sales window we want to check
     * @return true if the event is open for sales, false if explicitly closed
     */
    public boolean isEventOpenForSales(String eventId) {
        try {
            EventSummary summary = restClient.get()
                    .uri("/internal/events/{eventId}/status", eventId)
                    .retrieve()
                    .body(EventSummary.class);
            if (summary == null) {
                log.warn("Null response from ticket-service for eventId={}, failing open", eventId);
                return true;
            }
            return summary.isOpenForSales();
        } catch (Exception e) {
            log.warn("Event validation unavailable for eventId={}, failing open: {}", eventId, e.getMessage());
            return true; // fail-open
        }
    }
}
