package com.ticketing.pricing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class EffectivePriceResponse {
    private String     ticketId;
    private String     eventId;
    private BigDecimal facePrice;
    private BigDecimal surgeMultiplier;
    private BigDecimal effectivePrice;
}
