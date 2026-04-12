package com.ticketing.pricing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class UpdatePriceRuleRequest {

    private String eventName;

    @DecimalMin("0.00")
    private BigDecimal minPrice;

    @DecimalMin("0.00")
    private BigDecimal maxPrice;

    @DecimalMin("0.00")
    private BigDecimal currentPrice;

    private Integer totalTickets;

    private Integer soldTickets;

    private Instant eventDate;
}
