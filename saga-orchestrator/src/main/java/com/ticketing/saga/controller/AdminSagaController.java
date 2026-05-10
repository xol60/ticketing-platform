package com.ticketing.saga.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.saga.domain.SagaStateEntity;
import com.ticketing.saga.domain.SagaStateRepository;
import com.ticketing.saga.model.SagaStatus;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/admin/sagas")
@RequiredArgsConstructor
public class AdminSagaController {

    private static final Set<SagaStatus> TERMINAL =
            Set.of(SagaStatus.COMPLETED, SagaStatus.FAILED, SagaStatus.CANCELLED);

    private final SagaStateRepository sagaStateRepository;

    @GetMapping
    public ApiResponse<Page<SagaStateEntity>> listSagas(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<SagaStateEntity> result = sagaStateRepository
                .findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ApiResponse.ok(result, traceId);
    }

    @GetMapping("/active")
    public ApiResponse<Page<SagaStateEntity>> listActive(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<SagaStateEntity> result = sagaStateRepository
                .findByStatusNotIn(TERMINAL,
                                   PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ApiResponse.ok(result, traceId);
    }

    @GetMapping("/{sagaId}")
    public ApiResponse<SagaStateEntity> getSaga(
            @PathVariable String sagaId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        SagaStateEntity saga = sagaStateRepository.findById(sagaId)
                .orElseThrow(() -> new EntityNotFoundException("Saga not found: " + sagaId));
        return ApiResponse.ok(saga, traceId);
    }
}
