package com.ticketing.ticket.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
public class CreateTicketRequest {

    @NotBlank
    private String eventId;

    @NotBlank
    private String eventName;

    private String section;

    private String row;

    @NotBlank
    private String seat;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal facePrice;
}
