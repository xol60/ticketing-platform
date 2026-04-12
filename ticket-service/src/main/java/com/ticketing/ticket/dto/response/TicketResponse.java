package com.ticketing.ticket.dto.response;

import com.ticketing.ticket.domain.model.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter
public class TicketResponse {
    private String id;
    private String eventId;
    private String eventName;
    private String section;
    private String row;
    private String seat;
    private TicketStatus status;
    private BigDecimal facePrice;
    private BigDecimal lockedPrice;
    private String lockedByOrderId;
    private Instant reservedAt;
    private Instant confirmedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
