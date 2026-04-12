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

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal minPrice;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal maxPrice;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal currentPrice;

    @Positive
    private int totalTickets;

    private Instant eventDate;
}
