package com.ticketing.ticket.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.ticket.domain.model.Event;
import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.domain.repository.TicketRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTicketController {

    private final EventRepository  eventRepository;
    private final TicketRepository ticketRepository;

    @GetMapping("/events")
    public ApiResponse<Page<Event>> listEvents(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<Event> result = eventRepository
                .findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ApiResponse.ok(result, traceId);
    }

    @GetMapping("/events/{eventId}/tickets")
    public ApiResponse<List<Ticket>> listTicketsByEvent(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        List<Ticket> tickets = ticketRepository.findByEventId(eventId);
        return ApiResponse.ok(tickets, traceId);
    }

    @GetMapping("/tickets/{ticketId}")
    public ApiResponse<Ticket> getTicket(
            @PathVariable String ticketId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new EntityNotFoundException("Ticket not found: " + ticketId));
        return ApiResponse.ok(ticket, traceId);
    }
}
