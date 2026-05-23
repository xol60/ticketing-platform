package com.ticketing.ticket.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * One page of available tickets for an event, plus the pricing context the
 * client needs to display it.
 *
 * <p>The {@code surgeMultiplier} is echoed back so the UI can render a banner
 * like "Surge active: 1.5×" without making a separate call to pricing-service.
 *
 * <p>The {@code totalAvailable} count is included so the UI can render
 * pagination controls without a separate count query. It reflects state at
 * page render time — under flash-sale conditions it may be slightly stale
 * (a few hundred ms behind reality).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableTicketsPage {
    private String                       eventId;
    private BigDecimal                   surgeMultiplier;
    private int                          totalAvailable;
    private int                          size;
    private int                          page;
    private List<AvailableTicketSummary> tickets;
}
