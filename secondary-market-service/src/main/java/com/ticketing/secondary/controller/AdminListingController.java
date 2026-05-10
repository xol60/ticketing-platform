package com.ticketing.secondary.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.secondary.domain.model.Listing;
import com.ticketing.secondary.domain.model.ListingStatus;
import com.ticketing.secondary.domain.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/listings")
@RequiredArgsConstructor
public class AdminListingController {

    private final ListingRepository listingRepository;

    @GetMapping
    public ApiResponse<Page<Listing>> listListings(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    ListingStatus status,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        PageRequest pr = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Listing> result = (status != null)
                ? listingRepository.findByStatus(status, pr)
                : listingRepository.findAll(pr);
        return ApiResponse.ok(result, traceId);
    }
}
