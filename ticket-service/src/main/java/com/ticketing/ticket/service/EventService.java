package com.ticketing.ticket.service;

import com.ticketing.common.events.EventSearchIndexedEvent;
import com.ticketing.common.events.EventStatusChangedEvent;
import com.ticketing.ticket.domain.model.Event;
import com.ticketing.common.events.EventStatus;
import com.ticketing.common.exception.ErrorCode;
import com.ticketing.common.exception.TicketingException;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.domain.repository.TicketRepository;
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
    private final TicketRepository     ticketRepository;
    private final TicketEventPublisher publisher;

    @Transactional
    public EventStatusResponse createEvent(CreateEventRequest request, String ownerId, String traceId) {
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
                .ownerId(ownerId)
                .version(0L)
                .build();
        event = eventRepository.save(event);
        log.info("Event created id={} name={} owner={}", event.getId(), event.getName(), ownerId);
        publishStatusChanged(event, traceId);
        publishSearchIndexed(event, traceId);
        return toResponse(event);
    }

    @Transactional
    public EventStatusResponse updateEvent(String eventId, UpdateEventRequest request,
                                           String userId, String role, String traceId) {
        Event event = findOrThrow(eventId);
        assertCanManage(event, userId, role);
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
    public EventStatusResponse openEvent(String eventId, String userId, String role, String traceId) {
        return changeStatus(eventId, EventStatus.OPEN, userId, role, traceId);
    }

    @Transactional
    public EventStatusResponse cancelEvent(String eventId, String userId, String role, String traceId) {
        return changeStatus(eventId, EventStatus.CANCELLED, userId, role, traceId);
    }

    @Transactional
    public EventStatusResponse closeEvent(String eventId, String userId, String role, String traceId) {
        return changeStatus(eventId, EventStatus.SALES_CLOSED, userId, role, traceId);
    }

    @Transactional
    public EventStatusResponse completeEvent(String eventId, String userId, String role, String traceId) {
        return changeStatus(eventId, EventStatus.COMPLETED, userId, role, traceId);
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

    private EventStatusResponse changeStatus(String eventId, EventStatus newStatus,
                                             String userId, String role, String traceId) {
        Event event = findOrThrow(eventId);
        assertCanManage(event, userId, role);
        event.setStatus(newStatus);
        event = eventRepository.save(event);
        log.info("Event {} status changed to {}", eventId, newStatus);
        publishStatusChanged(event, traceId);
        publishSearchIndexed(event, traceId);
        return toResponse(event);
    }

    /**
     * Ownership guard for EVENT_OWNER-scoped mutations. ADMIN bypasses entirely;
     * an EVENT_OWNER may only touch events they own. A null/blank role or a
     * legacy event with no owner is treated as forbidden for non-admins so we
     * fail closed. Throws {@link TicketingException} with {@link ErrorCode#FORBIDDEN}
     * (→ 403 via GlobalExceptionHandler).
     */
    private void assertCanManage(Event event, String userId, String role) {
        if ("ADMIN".equals(role)) {
            return; // admins manage every event
        }
        if (userId != null && userId.equals(event.getOwnerId())) {
            return; // owner manages their own event
        }
        throw new TicketingException(ErrorCode.FORBIDDEN,
                "You do not have permission to manage event " + event.getId());
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
        // Ticket aggregates travel with the event because only this service can
        // compute them. Downstream read models must never try to infer crowd
        // size or price from the description — agent-service in particular uses
        // the count to disprove facets that claim an intimate room for a
        // stadium, which only works while the number is a fact rather than
        // another guess.
        var tickets = ticketRepository.summariseTickets(event.getId());

        publisher.publishEventSearchIndexed(new EventSearchIndexedEvent(
                traceId, null,
                event.getId(), event.getName(), event.getStatus().name(),
                event.getSalesOpenAt(), event.getSalesCloseAt(), event.getEventDate(),
                event.getPrimaryArtist(), event.getVenueName(), event.getVenueCity(),
                event.getShortDescription(), event.getFullDescription(),
                event.getCategory(), event.getGenre(),
                tickets == null ? null : tickets.getMinPrice(),
                tickets == null ? null : tickets.getMaxPrice(),
                tickets == null ? 0 : (int) tickets.getTicketCount()
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
                .ownerId(event.getOwnerId())
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
