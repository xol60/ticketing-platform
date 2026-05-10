package com.ticketing.order.client;

import com.ticketing.order.config.ClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Anti-Corruption Layer: HTTP client that calls reservation-service to verify
 * whether the requesting user is allowed to purchase a given ticket right now.
 *
 * <p><b>Fairness, not security.</b>  This check enforces queue ordering so that
 * a user who joined the queue and was promoted is not bypassed by someone
 * calling {@code POST /api/orders} directly.  It is <em>not</em> the guard
 * against overselling — the ticket-service pessimistic DB lock is.
 *
 * <p><b>Fail-open policy.</b>  If reservation-service is unreachable or returns
 * an unexpected response, this client returns {@code true} (allow).  Rationale:
 * the queue is a fairness mechanism; degrading to "anyone can order" during a
 * reservation-service outage is preferable to blocking all commerce.  The
 * ticket-service pessimistic lock still prevents double-selling.
 */
@Slf4j
@Component
public class ReservationAccessClient {

    private final RestClient restClient;

    public ReservationAccessClient(ClientProperties properties) {
        ClientProperties.ReservationService config = properties.getReservationService();

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
     * Returns {@code true} if the user may proceed to purchase the given ticket.
     *
     * <p>Specifically:
     * <ul>
     *   <li>{@code true}  — no active queue contention, OR the user holds the exclusive window.</li>
     *   <li>{@code false} — another user currently holds the exclusive purchase window.</li>
     *   <li>{@code true}  — reservation-service unavailable (fail-open, see class-level Javadoc).</li>
     * </ul>
     *
     * @param ticketId the ticket being purchased
     * @param userId   the user requesting the purchase
     */
    @SuppressWarnings("unchecked")
    public boolean isAllowedToPurchase(String ticketId, String userId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/internal/reservations/{ticketId}/promotion-check?userId={userId}",
                            ticketId, userId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.warn("Null response from reservation-service for ticketId={} userId={} — failing open",
                        ticketId, userId);
                return true;
            }
            return Boolean.TRUE.equals(response.get("allowed"));

        } catch (Exception e) {
            // Network errors, timeouts, 5xx — fail open
            log.warn("Reservation access check unavailable for ticketId={} userId={} — failing open: {}",
                    ticketId, userId, e.getMessage());
            return true;
        }
    }
}
