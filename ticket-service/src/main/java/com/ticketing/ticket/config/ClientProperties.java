package com.ticketing.ticket.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * URLs and timeouts for downstream services that ticket-service calls over HTTP.
 *
 * <p>Currently only pricing-service is reached this way — used by the
 * public "list available tickets for an event" endpoint to enrich the page
 * with the current effective price. The lookup is cached locally so the
 * actual HTTP call fires at most once per event per 30 seconds.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "clients")
public class ClientProperties {

    @Valid
    private PricingService pricingService = new PricingService();

    @Getter
    @Setter
    public static class PricingService {

        @NotBlank(message = "clients.pricing-service.url must be configured")
        private String url;

        @Positive
        private int connectTimeoutMs = 500;

        @Positive
        private int readTimeoutMs = 1000;
    }
}
