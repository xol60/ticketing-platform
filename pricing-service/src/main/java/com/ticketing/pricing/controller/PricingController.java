package com.ticketing.pricing.controller;

import com.ticketing.pricing.dto.request.CreatePriceRuleRequest;
import com.ticketing.pricing.dto.request.UpdatePriceRuleRequest;
import com.ticketing.pricing.dto.response.PriceRuleResponse;
import com.ticketing.pricing.service.PricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    @PostMapping("/rules")
    public ResponseEntity<PriceRuleResponse> createRule(
            @Valid @RequestBody CreatePriceRuleRequest request) {
        return ResponseEntity.ok(pricingService.createRule(request));
    }

    @GetMapping("/rules/{eventId}")
    public ResponseEntity<PriceRuleResponse> getRule(@PathVariable String eventId) {
        return ResponseEntity.ok(pricingService.getRule(eventId));
    }

    @PutMapping("/rules/{eventId}")
    public ResponseEntity<PriceRuleResponse> updateRule(
            @PathVariable String eventId,
            @Valid @RequestBody UpdatePriceRuleRequest request) {
        return ResponseEntity.ok(pricingService.updateRule(eventId, request));
    }

    // ── SSE stream ────────────────────────────────────────────────────────────

    @GetMapping(value = "/events/{eventId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPriceUpdates(@PathVariable String eventId) {
        return pricingService.registerSseEmitter(eventId);
    }
}
