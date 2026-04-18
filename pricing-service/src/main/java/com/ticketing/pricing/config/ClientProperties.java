package com.ticketing.pricing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "clients")
public class ClientProperties {

    @Valid
    private TicketService ticketService = new TicketService();

    @Getter
    @Setter
    public static class TicketService {

        @NotBlank(message = "clients.ticket-service.url must be configured")
        private String url;

        @Positive
        private int connectTimeoutMs = 500;

        @Positive
        private int readTimeoutMs = 1000;
    }
}
