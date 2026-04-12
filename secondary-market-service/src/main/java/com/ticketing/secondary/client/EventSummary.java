package com.ticketing.secondary.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal projection of ticket-service EventStatusResponse.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventSummary {
    private String  eventId;
    private boolean openForSales;
}
