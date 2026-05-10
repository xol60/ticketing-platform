package com.ticketing.notification.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.notification.domain.model.NotificationLog;
import com.ticketing.notification.domain.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationLogRepository repository;

    @GetMapping
    public ApiResponse<Page<NotificationLog>> listNotifications(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String type,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        PageRequest pr = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<NotificationLog> result = (type != null)
                ? repository.findByType(type, pr)
                : repository.findAll(pr);
        return ApiResponse.ok(result, traceId);
    }
}
