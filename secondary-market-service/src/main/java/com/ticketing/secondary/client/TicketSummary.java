package com.ticketing.secondary.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Minimal projection of a ticket returned by ticket-service.
 * Used only to enforce the secondary-market price cap (askPrice ≤ 2× facePrice).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketSummary {
    private String     id;
    private String     eventId;
    private BigDecimal facePrice;
}
