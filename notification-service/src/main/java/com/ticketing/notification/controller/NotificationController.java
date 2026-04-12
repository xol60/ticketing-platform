package com.ticketing.notification.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.notification.domain.model.NotificationLog;
import com.ticketing.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<NotificationLog>> getByRecipient(
            @RequestParam String recipient,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(notificationService.getByRecipient(recipient), traceId);
    }
}
