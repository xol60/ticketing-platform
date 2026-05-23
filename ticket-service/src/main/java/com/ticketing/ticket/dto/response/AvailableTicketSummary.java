package com.ticketing.ticket.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Public-facing lightweight ticket projection — what gets returned when a user
 * lists available tickets for an event. Deliberately omits internal fields
 * ({@code lockedByOrderId}, {@code reservedUntil}, {@code version}, etc.) so
 * the read path stays lean and the response payload stays small.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableTicketSummary {
    private String     id;
    private String     section;
    private String     row;
    private String     seat;
    private BigDecimal facePrice;
    /** {@code facePrice × eventSurgeMultiplier}. Computed at response time. */
    private BigDecimal effectivePrice;
}
