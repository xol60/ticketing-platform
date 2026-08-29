package com.ticketing.ticket.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.ticket.dto.request.CreateEventRequest;
import com.ticketing.ticket.dto.request.UpdateEventRequest;
import com.ticketing.ticket.dto.response.AvailableTicketsPage;
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
            @RequestHeader(value = "X-User-Id",  required = false) String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        // Caller (gateway-injected X-User-Id) becomes the owner of the new event.
        return ApiResponse.ok(eventService.createEvent(request, userId, traceId), traceId);
    }

    /**
     * Partial update of event metadata (name, description, artist, venue, etc.).
     * Status changes go through the dedicated lifecycle endpoints below.
     * Triggers a re-index in search-service via the {@code event.search.indexed} topic.
     */
    @PatchMapping("/api/tickets/events/{eventId}")
    public ApiResponse<EventStatusResponse> update(
            @PathVariable String eventId,
            @Valid @RequestBody UpdateEventRequest request,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Trace-Id",  required = false) String traceId) {
        return ApiResponse.ok(eventService.updateEvent(eventId, request, userId, role, traceId), traceId);
    }

    @PatchMapping("/api/tickets/events/{eventId}/open")
    public ApiResponse<EventStatusResponse> open(
            @PathVariable String eventId,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Trace-Id",  required = false) String traceId) {
        return ApiResponse.ok(eventService.openEvent(eventId, userId, role, traceId), traceId);
    }

    @PatchMapping("/api/tickets/events/{eventId}/cancel")
    public ApiResponse<EventStatusResponse> cancel(
            @PathVariable String eventId,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Trace-Id",  required = false) String traceId) {
        return ApiResponse.ok(eventService.cancelEvent(eventId, userId, role, traceId), traceId);
    }

    @PatchMapping("/api/tickets/events/{eventId}/close")
    public ApiResponse<EventStatusResponse> close(
            @PathVariable String eventId,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Trace-Id",  required = false) String traceId) {
        return ApiResponse.ok(eventService.closeEvent(eventId, userId, role, traceId), traceId);
    }

    @GetMapping("/api/tickets/events")
    public ApiResponse<List<EventStatusResponse>> listOpen(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(eventService.getOpenEvents(), traceId);
    }

    /**
     * Paginated list of AVAILABLE tickets for an event, with effective (surge-adjusted)
     * prices. Powers the event-detail page reached from a search-result click.
     *
     * <p>Defaults: 50 tickets per page, page 0. Pricing is fetched once per page from
     * pricing-service (Caffeine-cached for 30s) so browse traffic does not multiply
     * into per-ticket pricing calls.
     *
     * @param eventId  the event whose tickets to list
     * @param page     zero-based page index (default 0)
     * @param size     page size (default 50, hard-capped at 200 to protect the service)
     */
    @GetMapping("/api/tickets/events/{eventId}/tickets")
    public ApiResponse<AvailableTicketsPage> listAvailableTickets(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return ApiResponse.ok(
                ticketService.listAvailableTicketsByEvent(eventId, safePage, safeSize),
                traceId);
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
