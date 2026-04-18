package com.ticketing.secondary.client;

import com.ticketing.secondary.config.ClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Anti-Corruption Layer: HTTP client to ticket-service.
 *
 * isEventOpenForSales — fail-open (secondary market is advisory; saga is the final authority)
 * getTicketFacePrice  — fail-closed (we must know the face price to enforce the price cap)
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
     * Returns true if the event is open for secondary-market sales.
     * Fails open — unreachable ticket-service allows the purchase through;
     * the downstream saga guard in ticket-service is the final authority.
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
            return true;
        }
    }

    /**
     * Returns the face price for a ticket. Used to enforce the 2× price cap on listings.
     * Fails closed — if ticket-service is unreachable we reject the listing creation.
     *
     * @throws IllegalArgumentException if the ticket does not exist (404)
     * @throws IllegalStateException    if ticket-service is unreachable or returns invalid data
     */
    public BigDecimal getTicketFacePrice(String ticketId) {
        try {
            TicketSummary summary = restClient.get()
                    .uri("/api/tickets/{ticketId}", ticketId)
                    .retrieve()
                    .body(TicketSummary.class);
            if (summary == null || summary.getFacePrice() == null) {
                throw new IllegalStateException("Invalid ticket response for ticketId=" + ticketId);
            }
            log.debug("Fetched facePrice={} for ticketId={}", summary.getFacePrice(), ticketId);
            return summary.getFacePrice();
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Ticket not found: " + ticketId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("ticket-service unreachable when fetching facePrice ticketId={}: {}", ticketId, e.getMessage());
            throw new IllegalStateException("Cannot verify ticket price — ticket-service unavailable");
        }
    }
}
