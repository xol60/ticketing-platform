package com.ticketing.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.*;
import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.domain.model.TicketStatus;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.domain.repository.TicketRepository;
import com.ticketing.ticket.dto.request.CreateTicketRequest;
import com.ticketing.ticket.dto.request.UpdateTicketRequest;
import com.ticketing.ticket.dto.response.EventStatusResponse;
import com.ticketing.ticket.dto.response.TicketResponse;
import com.ticketing.ticket.kafka.TicketEventPublisher;
import com.ticketing.ticket.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final String LOCK_PREFIX    = "ticket:lock:";
    private static final String L2_PREFIX     = "ticket:cache:";
    private static final String L2_EVT_PREFIX = "ticket:cache:event:";
    private static final Duration LOCK_TTL    = Duration.ofSeconds(30);
    private static final Duration L2_TTL      = Duration.ofSeconds(30);

    private final TicketRepository      ticketRepository;
    private final TicketMapper          ticketMapper;
    private final TicketEventPublisher  eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;
    private final EventRepository        eventRepository;
    private final ObjectMapper          objectMapper;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets-event", key = "#request.eventId")
    })
    public TicketResponse createTicket(CreateTicketRequest request) {
        if (ticketRepository.existsByEventIdAndSectionAndRowAndSeat(
                request.getEventId(), request.getSection(),
                request.getRow(), request.getSeat())) {
            throw new IllegalArgumentException(
                "Ticket already exists for event=%s section=%s row=%s seat=%s"
                    .formatted(request.getEventId(), request.getSection(),
                               request.getRow(), request.getSeat()));
        }
        Ticket ticket = ticketMapper.toEntity(request);
        ticket.setStatus(TicketStatus.AVAILABLE);
        TicketResponse response = ticketMapper.toResponse(ticketRepository.save(ticket));
        // L2 evict event list
        redisTemplate.delete(L2_EVT_PREFIX + request.getEventId());
        return response;
    }

    /**
     * Read single ticket — L1 (Caffeine) → L2 (Redis) → DB.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "tickets", key = "#id")
    public TicketResponse getTicket(String id) {
        // L2 check
        String cached = redisTemplate.opsForValue().get(L2_PREFIX + id);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, TicketResponse.class);
            } catch (Exception e) {
                log.warn("L2 cache deserialize failed for ticket={}", id);
            }
        }
        // DB fallback
        TicketResponse response = ticketMapper.toResponse(findOrThrow(id));
        writeL2(L2_PREFIX + id, response);
        return response;
    }

    /**
     * Read all tickets for event — L1 (Caffeine) → L2 (Redis) → DB.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "tickets-event", key = "#eventId")
    public List<TicketResponse> getTicketsByEvent(String eventId) {
        String cached = redisTemplate.opsForValue().get(L2_EVT_PREFIX + eventId);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<TicketResponse>>() {});
            } catch (Exception e) {
                log.warn("L2 cache deserialize failed for event={}", eventId);
            }
        }
        List<TicketResponse> responses = ticketRepository.findByEventId(eventId).stream()
                .map(ticketMapper::toResponse)
                .collect(Collectors.toList());
        writeL2(L2_EVT_PREFIX + eventId, responses);
        return responses;
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> getAvailableTickets(String eventId) {
        return ticketRepository.findByEventIdAndStatus(eventId, TicketStatus.AVAILABLE).stream()
                .map(ticketMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets",       key = "#id"),
        @CacheEvict(value = "tickets-event", key = "#result.eventId", condition = "#result != null")
    })
    public TicketResponse updateTicket(String id, UpdateTicketRequest request) {
        Ticket ticket = findOrThrow(id);
        ticketMapper.updateEntityFromRequest(request, ticket);
        TicketResponse response = ticketMapper.toResponse(ticketRepository.save(ticket));
        evictL2(id, response.getEventId());
        return response;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets",       key = "#id"),
        @CacheEvict(value = "tickets-event", allEntries = true)
    })
    public void deleteTicket(String id) {
        Ticket ticket = findOrThrow(id);
        if (ticket.getStatus() == TicketStatus.RESERVED || ticket.getStatus() == TicketStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot delete a %s ticket".formatted(ticket.getStatus()));
        }
        evictL2(id, ticket.getEventId());
        ticketRepository.delete(ticket);
    }

    // ── Saga command handlers ────────────────────────────────────────────────

    @Transactional
    public void handleReserveCommand(TicketReserveCommand cmd) {
        String lockKey = LOCK_PREFIX + cmd.getTicketId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, cmd.getSagaId(), LOCK_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("Could not acquire lock for ticket={} saga={}", cmd.getTicketId(), cmd.getSagaId());
            eventPublisher.publishReleased(new TicketReleasedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), "LOCK_CONFLICT"));
            return;
        }

        try {
            Ticket ticket = ticketRepository.findByIdForUpdate(cmd.getTicketId())
                    .orElse(null);

            if (ticket == null || !ticket.isAvailable()) {
                log.warn("Ticket unavailable id={} saga={}", cmd.getTicketId(), cmd.getSagaId());
                eventPublisher.publishReleased(new TicketReleasedEvent(
                        cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(), "TICKET_UNAVAILABLE"));
                return;
            }

            // Validate event is still open for sales
            var event = eventRepository.findById(ticket.getEventId()).orElse(null);
            if (event != null && !event.isOpenForSales()) {
                log.warn("Event {} not open for sales, rejecting saga={}", ticket.getEventId(), cmd.getSagaId());
                eventPublisher.publishReleased(new TicketReleasedEvent(
                        cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(), "EVENT_NOT_OPEN"));
                return;
            }

            ticket.reserve(cmd.getOrderId(), cmd.getUserId(), ticket.getFacePrice());
            ticketRepository.save(ticket);
            evictL2(ticket.getId(), ticket.getEventId());

            eventPublisher.publishReserved(new TicketReservedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    ticket.getId(), cmd.getOrderId(),
                    cmd.getUserId(), ticket.getLockedPrice()));

            log.info("Ticket reserved id={} order={}", ticket.getId(), cmd.getOrderId());
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    @Transactional
    public void handleConfirmCommand(TicketConfirmCommand cmd) {
        Ticket ticket = ticketRepository.findByIdForUpdate(cmd.getTicketId())
                .orElse(null);

        if (ticket == null || ticket.getStatus() != TicketStatus.RESERVED
                || !cmd.getOrderId().equals(ticket.getLockedByOrderId())) {
            log.warn("Cannot confirm ticket id={} status={}", cmd.getTicketId(),
                     ticket == null ? "NOT_FOUND" : ticket.getStatus());
            return;
        }

        ticket.confirm(cmd.getOrderId());
        ticketRepository.save(ticket);
        evictL2(ticket.getId(), ticket.getEventId());

        eventPublisher.publishConfirmed(new TicketConfirmedEvent(
                cmd.getTraceId(), cmd.getSagaId(),
                ticket.getId(), cmd.getOrderId(),
                ticket.getLockedByUserId()));

        log.info("Ticket confirmed id={} order={}", ticket.getId(), cmd.getOrderId());
    }

    @Transactional
    public void handleReleaseCommand(TicketReleaseCommand cmd) {
        releaseTicket(cmd.getTicketId(), cmd.getOrderId(),
                      cmd.getTraceId(), cmd.getSagaId(), cmd.getReason());
    }

    @Transactional
    public void handleCompensation(SagaCompensateEvent event) {
        if (event.getTicketId() == null) return;
        releaseTicket(event.getTicketId(), event.getOrderId(),
                      event.getTraceId(), event.getSagaId(), "SAGA_COMPENSATION");
    }

    // ── Internal read for other services ───────────────────────────────────

    /**
     * Used by the internal HTTP endpoint so order-service / secondary-market-service
     * can check whether a ticket's event is open for sales before starting a saga.
     */
    @Transactional(readOnly = true)
    public EventStatusResponse getEventStatusByTicketId(String ticketId) {
        Ticket ticket = findOrThrow(ticketId);
        var event = eventRepository.findById(ticket.getEventId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Event not found for ticket: " + ticketId));
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

    // ── Internal helpers ────────────────────────────────────────────────────

    private void releaseTicket(String ticketId, String orderId,
                                String traceId, String sagaId, String reason) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId).orElse(null);

        if (ticket == null) {
            log.warn("Release: ticket not found id={}", ticketId);
            return;
        }
        if (ticket.getStatus() != TicketStatus.RESERVED
                || !orderId.equals(ticket.getLockedByOrderId())) {
            log.info("Release skipped: ticket={} status={} order mismatch",
                     ticketId, ticket.getStatus());
            return;
        }

        ticket.release();
        ticketRepository.save(ticket);
        evictL2(ticket.getId(), ticket.getEventId());

        eventPublisher.publishReleased(new TicketReleasedEvent(
                traceId, sagaId, ticketId, orderId, reason));

        log.info("Ticket released id={} order={} reason={}", ticketId, orderId, reason);
    }

    private Ticket findOrThrow(String id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Ticket not found: " + id));
    }

    // ── L2 Redis cache helpers ────────────────────────────────────────────────

    private void writeL2(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), L2_TTL);
        } catch (Exception e) {
            log.warn("L2 cache write failed key={}: {}", key, e.getMessage());
        }
    }

    /**
     * Evict both L2 entries for a ticket — single-ticket key and event-list key.
     * L1 (Caffeine) is evicted via @CacheEvict on the write methods.
     * For Saga command handlers (not annotated), we evict L2 manually here.
     */
    private void evictL2(String ticketId, String eventId) {
        try {
            redisTemplate.delete(L2_PREFIX + ticketId);
            if (eventId != null) {
                redisTemplate.delete(L2_EVT_PREFIX + eventId);
            }
        } catch (Exception e) {
            log.warn("L2 cache evict failed ticketId={}: {}", ticketId, e.getMessage());
        }
    }
}
