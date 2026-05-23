package com.ticketing.ticket.client;

import com.ticketing.ticket.config.ClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Calls pricing-service to fetch the current surge multiplier for an event.
 *
 * <h3>Why this exists</h3>
 * The "list available tickets for an event" endpoint shows the effective price of
 * each ticket = {@code facePrice × surgeMultiplier}. The multiplier is event-scoped,
 * not ticket-scoped — so we batch by fetching ONCE per event per page render and
 * multiply locally for every ticket. This collapses what would have been an
 * N-call fan-out (one per ticket) into a single call per page.
 *
 * <h3>Caching</h3>
 * Results are cached in Caffeine under {@code event-multiplier} for 30 seconds
 * — matching pricing-service's own recalculation cadence. So even under heavy
 * browse traffic (10k concurrent users on one event), pricing-service receives
 * at most one call per event every 30 seconds.
 *
 * <h3>Failure mode</h3>
 * If pricing-service is unreachable the client returns {@link Optional#empty()}
 * and the caller falls back to face price (effectivePrice == facePrice).
 * Worse search UX than showing the surge price, but never worse than refusing
 * to serve the page.
 */
@Slf4j
@Component
public class PricingClient {

    private final RestClient restClient;

    public PricingClient(ClientProperties properties) {
        ClientProperties.PricingService config = properties.getPricingService();

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
     * Fetches the current surge multiplier for {@code eventId}.
     * The 30-second Caffeine cache means callers can invoke this freely per
     * page render without hammering pricing-service.
     *
     * @return the multiplier (e.g. {@code 1.5} for a 1.5× surge), or empty if
     *         the rule could not be fetched. Callers should default to
     *         {@link BigDecimal#ONE} (face price) on empty.
     */
    // Spring Cache automatically unwraps Optional return values:
    //   • Optional.of(x)   → cached as x (the BigDecimal)
    //   • Optional.empty() → not cached at all (no put fires)
    // So inside the `unless` SpEL #result is the unwrapped BigDecimal (or null
    // if the method threw / returned empty). The old guard "#result.isEmpty()"
    // therefore tried to call isEmpty() on a BigDecimal and failed with
    // SpEL EL1004E. Reduced to a plain null-check now that we know the
    // unwrapping rule.
    @Cacheable(value = "event-multiplier", key = "#eventId",
               unless = "#result == null")
    public Optional<BigDecimal> getCurrentMultiplier(String eventId) {
        try {
            PriceRuleSummary summary = restClient.get()
                    .uri("/api/pricing/rules/{eventId}", eventId)
                    .retrieve()
                    .body(PriceRuleSummary.class);
            if (summary == null || summary.getSurgeMultiplier() == null) {
                log.warn("pricing-service returned null/empty for eventId={}", eventId);
                return Optional.empty();
            }
            return Optional.of(summary.getSurgeMultiplier());
        } catch (Exception e) {
            log.warn("pricing-service unreachable for eventId={}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }
}
