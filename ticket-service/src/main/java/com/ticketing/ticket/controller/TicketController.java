package com.ticketing.ticket.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.ticket.dto.request.CreateTicketBatchRequest;
import com.ticketing.ticket.dto.request.CreateTicketRequest;
import com.ticketing.ticket.dto.request.UpdateTicketRequest;
import com.ticketing.ticket.dto.response.TicketResponse;
import com.ticketing.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(ticketService.createTicket(request), traceId);
    }

    /**
     * Bulk-create tickets from a seat-range definition.
     * Returns the list of tickets that were actually created
     * (duplicates that already exist are silently skipped).
     */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<TicketResponse>> createBatch(
            @Valid @RequestBody CreateTicketBatchRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(ticketService.createTicketsBatch(request), traceId);
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketResponse> getById(
            @PathVariable String id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(ticketService.getTicket(id), traceId);
    }

    @GetMapping
    public ApiResponse<List<TicketResponse>> getByEvent(
            @RequestParam String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(ticketService.getTicketsByEvent(eventId), traceId);
    }

    @GetMapping("/available")
    public ApiResponse<List<TicketResponse>> getAvailable(
            @RequestParam String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(ticketService.getAvailableTickets(eventId), traceId);
    }

    @PutMapping("/{id}")
    public ApiResponse<TicketResponse> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateTicketRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(ticketService.updateTicket(id, request), traceId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        ticketService.deleteTicket(id);
    }
}
