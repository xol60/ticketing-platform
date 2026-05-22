package com.ticketing.ticket.service;

import com.ticketing.common.events.EventSearchIndexedEvent;
import com.ticketing.common.events.EventStatusChangedEvent;
import com.ticketing.ticket.domain.model.Event;
import com.ticketing.ticket.domain.model.EventStatus;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.dto.request.CreateEventRequest;
import com.ticketing.ticket.dto.request.UpdateEventRequest;
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

    private final EventRepository      eventRepository;
    private final TicketEventPublisher publisher;

    @Transactional
    public EventStatusResponse createEvent(CreateEventRequest request, String traceId) {
        Event event = Event.builder()
                .name(request.getName())
                .status(EventStatus.DRAFT)
                .salesOpenAt(request.getSalesOpenAt())
                .salesCloseAt(request.getSalesCloseAt())
                .eventDate(request.getEventDate())
                .primaryArtist(request.getPrimaryArtist())
                .venueName(request.getVenueName())
                .venueCity(request.getVenueCity())
                .shortDescription(request.getShortDescription())
                .fullDescription(request.getFullDescription())
                .category(request.getCategory())
                .genre(request.getGenre())
                .version(0L)
                .build();
        event = eventRepository.save(event);
        log.info("Event created id={} name={}", event.getId(), event.getName());
        publishStatusChanged(event, traceId);
        publishSearchIndexed(event, traceId);
        return toResponse(event);
    }

    @Transactional
    public EventStatusResponse updateEvent(String eventId, UpdateEventRequest request, String traceId) {
        Event event = findOrThrow(eventId);
        if (request.getName()             != null) event.setName(request.getName());
        if (request.getSalesOpenAt()      != null) event.setSalesOpenAt(request.getSalesOpenAt());
        if (request.getSalesCloseAt()     != null) event.setSalesCloseAt(request.getSalesCloseAt());
        if (request.getEventDate()        != null) event.setEventDate(request.getEventDate());
        if (request.getPrimaryArtist()    != null) event.setPrimaryArtist(request.getPrimaryArtist());
        if (request.getVenueName()        != null) event.setVenueName(request.getVenueName());
        if (request.getVenueCity()        != null) event.setVenueCity(request.getVenueCity());
        if (request.getShortDescription() != null) event.setShortDescription(request.getShortDescription());
        if (request.getFullDescription()  != null) event.setFullDescription(request.getFullDescription());
        if (request.getCategory()         != null) event.setCategory(request.getCategory());
        if (request.getGenre()            != null) event.setGenre(request.getGenre());
        event = eventRepository.save(event);
        log.info("Event metadata updated id={}", eventId);
        // Metadata-only edits don't trigger publishStatusChanged (no state-machine transition)
        // but they DO need to update the search index.
        publishSearchIndexed(event, traceId);
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
        publishSearchIndexed(event, traceId);
        return toResponse(event);
    }

    private void publishStatusChanged(Event event, String traceId) {
        publisher.publishEventStatusChanged(new EventStatusChangedEvent(
                traceId, null,
                event.getId(), event.getName(), event.getStatus().name(),
                event.getSalesOpenAt(), event.getSalesCloseAt(), event.getEventDate()
        ));
    }

    /**
     * Publish the full searchable payload to {@code event.search.indexed}.
     * The search-service consumer upserts/deletes the ES document based on status.
     */
    private void publishSearchIndexed(Event event, String traceId) {
        publisher.publishEventSearchIndexed(new EventSearchIndexedEvent(
                traceId, null,
                event.getId(), event.getName(), event.getStatus().name(),
                event.getSalesOpenAt(), event.getSalesCloseAt(), event.getEventDate(),
                event.getPrimaryArtist(), event.getVenueName(), event.getVenueCity(),
                event.getShortDescription(), event.getFullDescription(),
                event.getCategory(), event.getGenre()
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
                .primaryArtist(event.getPrimaryArtist())
                .venueName(event.getVenueName())
                .venueCity(event.getVenueCity())
                .shortDescription(event.getShortDescription())
                .fullDescription(event.getFullDescription())
                .category(event.getCategory())
                .genre(event.getGenre())
                .build();
    }
}
