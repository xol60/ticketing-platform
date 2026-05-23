package com.ticketing.ticket.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Minimal subset of pricing-service's {@code PriceRuleResponse} that ticket-service
 * cares about — just the current effective surge multiplier. Other fields
 * (maxSurge, demandFactor, soldTickets, etc.) are ignored to keep the contract narrow.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PriceRuleSummary {
    private String     eventId;
    private BigDecimal surgeMultiplier;
}
