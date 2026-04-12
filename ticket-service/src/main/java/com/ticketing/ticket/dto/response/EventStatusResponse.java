package com.ticketing.ticket.dto.response;

import com.ticketing.ticket.domain.model.EventStatus;
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
}
