package com.ticketing.order.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer: HTTP client that calls ticket-service to verify
 * whether the event associated with a ticket is currently open for sales.
 *
 * Behaviour:
 *  - Returns false if ticket-service explicitly says the event is closed.
 *  - Returns true (fail-open) if ticket-service is unreachable or returns an error,
 *    because the saga orchestrator will perform its own guard check once the
 *    reserve command reaches ticket-service.
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
     * @param ticketId the ticket being ordered
     * @return true if the event is open for sales, false if explicitly closed
     */
    public boolean isEventOpenForSales(String ticketId) {
        try {
            EventSummary summary = restClient.get()
                    .uri("/internal/tickets/{ticketId}/event-status", ticketId)
                    .retrieve()
                    .body(EventSummary.class);
            if (summary == null) {
                log.warn("Null response from ticket-service for ticketId={}, failing open", ticketId);
                return true;
            }
            return summary.isOpenForSales();
        } catch (Exception e) {
            log.warn("Event validation unavailable for ticketId={}, failing open: {}", ticketId, e.getMessage());
            return true; // fail-open — saga guard is the final authority
        }
    }
}
