package com.ticketing.order.client;

import com.ticketing.order.config.ClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer: HTTP client that calls ticket-service to verify
 * whether the event associated with a ticket is currently open for sales.
 *
 * Behaviour:
 *  - Returns false if ticket-service explicitly says the event is closed.
 *  - Returns true (fail-open) if ticket-service is unreachable, times out, or errors —
 *    because the saga orchestrator will perform its own guard check once the
 *    reserve command reaches ticket-service.
 */
@Slf4j
@Component
public class EventValidationClient {

    private final RestClient restClient;

    public EventValidationClient(ClientProperties properties) {
        ClientProperties.TicketService config = properties.getTicketService();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(config.getConnectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(config.getReadTimeoutMs()))
                .build();

        var httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .build();

        this.restClient = RestClient.builder()
                .baseUrl(config.getUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
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
