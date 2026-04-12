package com.ticketing.ticket.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.ticket.dto.request.CreateEventRequest;
import com.ticketing.ticket.dto.response.EventStatusResponse;
import com.ticketing.ticket.service.EventService;
import com.ticketing.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService  eventService;
    private final TicketService ticketService;

    // ── Admin endpoints (/api/tickets/events/**)  ─────────────────────────────

    @PostMapping("/api/tickets/events")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EventStatusResponse> create(
            @Valid @RequestBody CreateEventRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(eventService.createEvent(request, traceId), traceId);
    }

    @PatchMapping("/api/tickets/events/{eventId}/open")
    public ApiResponse<EventStatusResponse> open(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(eventService.openEvent(eventId, traceId), traceId);
    }

    @PatchMapping("/api/tickets/events/{eventId}/cancel")
    public ApiResponse<EventStatusResponse> cancel(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(eventService.cancelEvent(eventId, traceId), traceId);
    }

    @PatchMapping("/api/tickets/events/{eventId}/close")
    public ApiResponse<EventStatusResponse> close(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(eventService.closeEvent(eventId, traceId), traceId);
    }

    @GetMapping("/api/tickets/events")
    public ApiResponse<List<EventStatusResponse>> listOpen(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(eventService.getOpenEvents(), traceId);
    }

    // ── Internal endpoints (called by order-service / secondary-market) ────────

    /** Resolve event status by eventId — used by secondary-market-service. */
    @GetMapping("/internal/events/{eventId}/status")
    public EventStatusResponse getStatus(@PathVariable String eventId) {
        return eventService.getEvent(eventId);
    }

    /** Resolve event status by ticketId — used by order-service (which only knows ticketId). */
    @GetMapping("/internal/tickets/{ticketId}/event-status")
    public EventStatusResponse getStatusByTicket(@PathVariable String ticketId) {
        return ticketService.getEventStatusByTicketId(ticketId);
    }
}
