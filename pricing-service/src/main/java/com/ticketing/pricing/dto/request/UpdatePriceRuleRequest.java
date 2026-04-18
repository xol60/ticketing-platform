package com.ticketing.pricing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class UpdatePriceRuleRequest {

    private String eventName;

    @DecimalMin("1.0")
    private BigDecimal maxSurge;

    private Integer totalTickets;

    private Instant eventDate;
}
