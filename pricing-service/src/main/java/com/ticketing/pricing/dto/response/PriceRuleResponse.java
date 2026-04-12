package com.ticketing.pricing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class PriceRuleResponse {

    private String id;
    private String eventId;
    private String eventName;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private BigDecimal currentPrice;
    private double demandFactor;
    private int totalTickets;
    private int soldTickets;
    private Instant eventDate;
    private Instant updatedAt;
}
