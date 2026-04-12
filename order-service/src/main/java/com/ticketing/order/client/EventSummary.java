package com.ticketing.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal projection of ticket-service EventStatusResponse.
 * Only the fields order-service cares about are mapped;
 * Jackson ignores the rest.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventSummary {
    private String  eventId;
    private boolean openForSales;
}
