package com.ticketing.ticket.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

@Data
public class CreateEventRequest {
    @NotBlank
    private String name;

    @NotNull
    private Instant salesOpenAt;

    @NotNull
    private Instant salesCloseAt;

    @NotNull
    @Future
    private Instant eventDate;
}
