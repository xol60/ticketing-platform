package com.ticketing.pricing.controller;

import com.ticketing.pricing.dto.request.CreatePriceRuleRequest;
import com.ticketing.pricing.dto.request.UpdatePriceRuleRequest;
import com.ticketing.pricing.dto.response.EffectivePriceResponse;
import com.ticketing.pricing.dto.response.PriceRuleResponse;
import com.ticketing.pricing.service.PricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    @PostMapping("/rules")
    public ResponseEntity<PriceRuleResponse> createRule(
            @Valid @RequestBody CreatePriceRuleRequest request,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return ResponseEntity.ok(pricingService.createRule(request, userId, role));
    }

    @GetMapping("/rules/{eventId}")
    public ResponseEntity<PriceRuleResponse> getRule(@PathVariable String eventId) {
        return ResponseEntity.ok(pricingService.getRule(eventId));
    }

    @PutMapping("/rules/{eventId}")
    public ResponseEntity<PriceRuleResponse> updateRule(
            @PathVariable String eventId,
            @Valid @RequestBody UpdatePriceRuleRequest request,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return ResponseEntity.ok(pricingService.updateRule(eventId, request, userId, role));
    }

    // ── Effective price ───────────────────────────────────────────────────────

    /**
     * Returns the effective price for a specific ticket.
     * facePrice is fetched from ticket-service (cached 10 min) and multiplied by the current surgeMultiplier.
     */
    @GetMapping("/tickets/{ticketId}/price")
    public ResponseEntity<EffectivePriceResponse> getEffectivePrice(@PathVariable String ticketId) {
        return ResponseEntity.ok(pricingService.getEffectivePrice(ticketId));
    }
}
