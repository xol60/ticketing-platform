package com.ticketing.order.config;

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

    @Valid
    private ReservationService reservationService = new ReservationService();

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

    @Getter
    @Setter
    public static class ReservationService {

        @NotBlank(message = "clients.reservation-service.url must be configured")
        private String url;

        @Positive
        private int connectTimeoutMs = 300;

        /** Kept short — this is a Redis-backed check, should be sub-millisecond on the server side. */
        @Positive
        private int readTimeoutMs = 800;
    }
}
