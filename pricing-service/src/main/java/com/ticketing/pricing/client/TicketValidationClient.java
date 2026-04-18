package com.ticketing.pricing.client;

import com.ticketing.pricing.config.CacheConfig;
import com.ticketing.pricing.config.ClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;


/**
 * Validates that an event exists in ticket-service before a price rule is created.
 * Fail-closed: if the event doesn't exist or the call fails, throws an exception.
 */
@Slf4j
@Component
public class TicketValidationClient {

    private final RestClient restClient;

    public TicketValidationClient(ClientProperties properties) {
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
     * Validates the event exists. Throws if not found or unreachable.
     *
     * @throws IllegalArgumentException if event does not exist (404)
     * @throws IllegalStateException    if ticket-service is unreachable
     */
    public void validateEventExists(String eventId) {
        try {
            restClient.get()
                    .uri("/internal/events/{eventId}/status", eventId)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Event {} confirmed to exist in ticket-service", eventId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Event not found: " + eventId);
        } catch (Exception e) {
            log.error("ticket-service unreachable when validating eventId={}: {}", eventId, e.getMessage());
            throw new IllegalStateException("Cannot validate event — ticket-service unavailable");
        }
    }

    /**
     * Fetches ticket summary (facePrice + eventId) from ticket-service.
     * Cached in Caffeine for 10 minutes — facePrice and eventId rarely change.
     */
    @Cacheable(value = CacheConfig.FACE_PRICE_CACHE, key = "#ticketId")
    public TicketSummary getTicketSummary(String ticketId) {
        try {
            TicketSummary summary = restClient.get()
                    .uri("/api/tickets/{ticketId}", ticketId)
                    .retrieve()
                    .body(TicketSummary.class);
            if (summary == null || summary.getFacePrice() == null) {
                throw new IllegalStateException("Invalid response from ticket-service for ticketId=" + ticketId);
            }
            log.debug("Fetched ticketSummary facePrice={} eventId={} for ticketId={}",
                    summary.getFacePrice(), summary.getEventId(), ticketId);
            return summary;
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Ticket not found: " + ticketId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("ticket-service unreachable when fetching ticket ticketId={}: {}", ticketId, e.getMessage());
            throw new IllegalStateException("Cannot fetch ticket — ticket-service unavailable");
        }
    }

    public BigDecimal getFacePrice(String ticketId) {
        return getTicketSummary(ticketId).getFacePrice();
    }

    public String getEventId(String ticketId) {
        return getTicketSummary(ticketId).getEventId();
    }
}
