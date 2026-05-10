package com.ticketing.pricing.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.pricing.domain.model.EventPriceRule;
import com.ticketing.pricing.domain.repository.EventPriceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/price-rules")
@RequiredArgsConstructor
public class AdminPricingController {

    private final EventPriceRuleRepository repository;

    @GetMapping
    public ApiResponse<Page<EventPriceRule>> listRules(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<EventPriceRule> result = repository
                .findAll(PageRequest.of(page, size, Sort.by("updatedAt").descending()));
        return ApiResponse.ok(result, traceId);
    }
}
