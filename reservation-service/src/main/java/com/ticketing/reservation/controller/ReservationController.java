package com.ticketing.reservation.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> joinQueue(
            @RequestParam String ticketId,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        var reservation = reservationService.joinQueue(ticketId, userId);
        return ApiResponse.ok(Map.of(
                "reservationId", reservation.getId().toString(),
                "ticketId", reservation.getTicketId(),
                "status", reservation.getStatus()
        ), traceId);
    }

    @DeleteMapping("/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveQueue(
            @PathVariable String reservationId,
            @RequestHeader("X-User-Id") String userId) {
        reservationService.leaveQueue(reservationId, userId);
    }

    @GetMapping("/{ticketId}/position")
    public ApiResponse<Map<String, Long>> getPosition(
            @PathVariable String ticketId,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        long position = reservationService.getQueuePosition(ticketId, userId);
        return ApiResponse.ok(Map.of("position", position), traceId);
    }
}
