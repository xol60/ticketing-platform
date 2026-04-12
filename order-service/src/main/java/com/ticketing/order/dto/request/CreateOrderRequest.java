package com.ticketing.order.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class CreateOrderRequest {

    @NotBlank(message = "ticketId is required")
    private String ticketId;

    @NotNull(message = "requestedPrice is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "requestedPrice must be positive")
    private BigDecimal requestedPrice;
}
