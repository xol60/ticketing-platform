package com.ticketing.secondary.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
public class CreateListingRequest {

    @NotBlank
    private String ticketId;

    @NotBlank
    private String eventId;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal askPrice;
}
