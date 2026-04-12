package com.ticketing.secondary.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.secondary.dto.request.CreateListingRequest;
import com.ticketing.secondary.dto.response.ListingResponse;
import com.ticketing.secondary.service.SecondaryMarketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/secondary/listings")
@RequiredArgsConstructor
public class SecondaryMarketController {

    private final SecondaryMarketService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ListingResponse> create(
            @Valid @RequestBody CreateListingRequest request,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.createListing(request, userId), traceId);
    }

    @GetMapping
    public ApiResponse<List<ListingResponse>> getByEvent(
            @RequestParam String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.getListingsByEvent(eventId), traceId);
    }

    @GetMapping("/{id}")
    public ApiResponse<ListingResponse> getById(
            @PathVariable String id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.getListing(id), traceId);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<ListingResponse> cancel(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.cancelListing(id, userId), traceId);
    }

    @PostMapping("/{id}/purchase")
    public ApiResponse<ListingResponse> purchase(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String buyerId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.purchaseListing(id, buyerId, traceId), traceId);
    }
}
