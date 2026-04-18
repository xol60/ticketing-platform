package com.ticketing.pricing.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class CreatePriceRuleRequest {

    @NotBlank
    private String eventId;

    @NotBlank
    private String eventName;

    /**
     * Maximum surge multiplier for this event (e.g. 1.5 = price can go up to 50% above facePrice).
     * Must be >= 1.0.
     */
    @NotNull
    @DecimalMin("1.0")
    private BigDecimal maxSurge;

    @Positive
    private int totalTickets;

    private Instant eventDate;
}
