package com.ticketing.ticket.service;

import com.ticketing.common.events.EventStatusChangedEvent;
import com.ticketing.ticket.domain.model.Event;
import com.ticketing.ticket.domain.model.EventStatus;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.dto.request.CreateEventRequest;
import com.ticketing.ticket.dto.response.EventStatusResponse;
import com.ticketing.ticket.kafka.TicketEventPublisher;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository    eventRepository;
    private final TicketEventPublisher publisher;

    @Transactional
    public EventStatusResponse createEvent(CreateEventRequest request, String traceId) {
        Event event = Event.builder()
                .name(request.getName())
                .status(EventStatus.DRAFT)
                .salesOpenAt(request.getSalesOpenAt())
                .salesCloseAt(request.getSalesCloseAt())
                .eventDate(request.getEventDate())
                .version(0L)
                .build();
        event = eventRepository.save(event);
        log.info("Event created id={} name={}", event.getId(), event.getName());
        publishStatusChanged(event, traceId);
        return toResponse(event);
    }

    @Transactional
    public EventStatusResponse openEvent(String eventId, String traceId) {
        return changeStatus(eventId, EventStatus.OPEN, traceId);
    }

    @Transactional
    public EventStatusResponse cancelEvent(String eventId, String traceId) {
        return changeStatus(eventId, EventStatus.CANCELLED, traceId);
    }

    @Transactional
    public EventStatusResponse closeEvent(String eventId, String traceId) {
        return changeStatus(eventId, EventStatus.SALES_CLOSED, traceId);
    }

    @Transactional
    public EventStatusResponse completeEvent(String eventId, String traceId) {
        return changeStatus(eventId, EventStatus.COMPLETED, traceId);
    }

    @Transactional(readOnly = true)
    public EventStatusResponse getEvent(String eventId) {
        return toResponse(findOrThrow(eventId));
    }

    @Transactional(readOnly = true)
    public List<EventStatusResponse> getOpenEvents() {
        return eventRepository.findByStatus(EventStatus.OPEN)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private EventStatusResponse changeStatus(String eventId, EventStatus newStatus, String traceId) {
        Event event = findOrThrow(eventId);
        event.setStatus(newStatus);
        event = eventRepository.save(event);
        log.info("Event {} status changed to {}", eventId, newStatus);
        publishStatusChanged(event, traceId);
        return toResponse(event);
    }

    private void publishStatusChanged(Event event, String traceId) {
        publisher.publishEventStatusChanged(new EventStatusChangedEvent(
                traceId, null,
                event.getId(), event.getName(), event.getStatus().name(),
                event.getSalesOpenAt(), event.getSalesCloseAt(), event.getEventDate()
        ));
    }

    public Event findOrThrow(String eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("Event not found: " + eventId));
    }

    private EventStatusResponse toResponse(Event event) {
        return EventStatusResponse.builder()
                .eventId(event.getId())
                .name(event.getName())
                .status(event.getStatus())
                .salesOpenAt(event.getSalesOpenAt())
                .salesCloseAt(event.getSalesCloseAt())
                .eventDate(event.getEventDate())
                .openForSales(event.isOpenForSales())
                .build();
    }
}
