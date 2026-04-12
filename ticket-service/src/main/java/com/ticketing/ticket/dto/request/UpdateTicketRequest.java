package com.ticketing.ticket.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
public class UpdateTicketRequest {

    private String eventName;

    private String section;

    private String row;

    private String seat;

    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal facePrice;
}
