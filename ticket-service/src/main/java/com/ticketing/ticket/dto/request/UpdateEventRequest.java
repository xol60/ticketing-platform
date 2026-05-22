package com.ticketing.ticket.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

/**
 * Partial update for an existing event. All fields are optional — only non-null
 * fields are applied. Used by {@code PATCH /api/tickets/events/{eventId}}.
 *
 * <p>Status changes are deliberately NOT updated via this DTO — they go through
 * the dedicated lifecycle endpoints ({@code /open}, {@code /cancel}, {@code /close},
 * {@code /complete}) so the state machine remains explicit.
 */
@Data
public class UpdateEventRequest {

    @Size(max = 255) private String  name;
    private Instant salesOpenAt;
    private Instant salesCloseAt;
    private Instant eventDate;

    @Size(max = 255)  private String primaryArtist;
    @Size(max = 255)  private String venueName;
    @Size(max = 100)  private String venueCity;
    @Size(max = 500)  private String shortDescription;
    @Size(max = 8000) private String fullDescription;
    @Size(max = 50)   private String category;
    @Size(max = 50)   private String genre;
}
