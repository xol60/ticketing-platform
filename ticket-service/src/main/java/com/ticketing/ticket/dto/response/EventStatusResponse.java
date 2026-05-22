package com.ticketing.ticket.dto.response;

import com.ticketing.common.events.EventStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventStatusResponse {
    private String      eventId;
    private String      name;
    private EventStatus status;
    private Instant     salesOpenAt;
    private Instant     salesCloseAt;
    private Instant     eventDate;
    private boolean     openForSales;

    // ── Searchable metadata (echoed back for admin UI / search) ──
    private String primaryArtist;
    private String venueName;
    private String venueCity;
    private String shortDescription;
    private String fullDescription;
    private String category;
    private String genre;
}
