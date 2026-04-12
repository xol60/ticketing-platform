package com.ticketing.secondary.dto.response;

import com.ticketing.secondary.domain.model.ListingStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter
public class ListingResponse {
    private String        id;
    private String        ticketId;
    private String        sellerId;
    private String        eventId;
    private BigDecimal    askPrice;
    private ListingStatus status;
    private Instant       createdAt;
    private Instant       updatedAt;
}
