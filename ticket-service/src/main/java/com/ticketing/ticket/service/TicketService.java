package com.ticketing.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.*;
import com.ticketing.common.exception.ErrorCode;
import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.domain.model.TicketStatus;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.domain.repository.TicketRepository;
import com.ticketing.ticket.dto.request.CreateTicketBatchRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
     * Bulk-create tickets from a seat-range definition.
     * Expands (rowStart..rowEnd) × (seatStart..seatEnd) and skips any seat
     * that already exists for the given event + section combination.
     * Uses a single {@code saveAll()} call for efficiency.
     */
    @Transactional
    @CacheEvict(value = "tickets-event", key = "#request.eventId")
    public List<TicketResponse> createTicketsBatch(CreateTicketBatchRequest request) {

        // ── 1. Generate row list ─────────────────────────────────────────────
        List<String> rows = generateRowRange(request.getRowStart(), request.getRowEnd());

        // ── 2. Generate seat list ────────────────────────────────────────────
        List<String> seats = new ArrayList<>();
        for (int s = request.getSeatStart(); s <= request.getSeatEnd(); s++) {
            seats.add(String.valueOf(s));
        }

        // ── 3. Build a set of existing (row:seat) keys for this event/section.
        //       Uses a projection query — only fetches row + seat columns,
        //       not full Ticket entities, which is much lighter for large events.
        String section = nullIfBlank(request.getSection());
        List<TicketRepository.SeatKey> seatKeys = (section == null)
                ? ticketRepository.findSeatKeysByEventIdAndSectionNull(request.getEventId())
                : ticketRepository.findSeatKeysByEventIdAndSection(request.getEventId(), section);
        Set<String> existing = seatKeys.stream()
                .map(sk -> dupKey(sk.getRow(), sk.getSeat()))
                .collect(Collectors.toSet());

        // ── 4. Build new Ticket entities, skipping duplicates ────────────────
        List<Ticket> toSave = new ArrayList<>();
        for (String row : rows) {
            String normalizedRow = nullIfBlank(row);
            for (String seat : seats) {
                if (!existing.contains(dupKey(normalizedRow, seat))) {
                    Ticket t = Ticket.builder()
                            .eventId(request.getEventId())
                            .eventName(request.getEventName())
                            .section(section)
                            .row(normalizedRow)
                            .seat(seat)
                            .facePrice(request.getFacePrice())
                            .status(TicketStatus.AVAILABLE)
                            .build();
                    toSave.add(t);
                }
            }
        }

        if (toSave.isEmpty()) {
            log.info("Batch insert: all {} potential seats already exist for event={}",
                     rows.size() * seats.size(), request.getEventId());
            return List.of();
        }

        // ── 5. Persist in one shot + evict L2 event cache ────────────────────
        List<Ticket> saved = ticketRepository.saveAll(toSave);
        redisTemplate.delete(L2_EVT_PREFIX + request.getEventId());

        log.info("Batch insert: created {} tickets for event={} section={}",
                 saved.size(), request.getEventId(), request.getSection());

        return saved.stream().map(ticketMapper::toResponse).collect(Collectors.toList());
    }

    // ── Range-generation helpers ─────────────────────────────────────────────

    /**
     * Expands a row range:
     * <ul>
     *   <li>"A"–"D"  → ["A","B","C","D"]  (single-letter alphabetical)</li>
     *   <li>"1"–"5"  → ["1","2","3","4","5"] (numeric)</li>
     *   <li>null/blank for both → [""] (no row dimension)</li>
     * </ul>
     */
    private List<String> generateRowRange(String rowStart, String rowEnd) {
        boolean hasRange = rowStart != null && !rowStart.isBlank()
                        && rowEnd   != null && !rowEnd.isBlank();
        if (!hasRange) {
            return List.of("");   // single iteration, row will be stored as null
        }

        // Detect alphabetical vs numeric range
        boolean alphabetic = rowStart.length() == 1 && Character.isLetter(rowStart.charAt(0))
                          && rowEnd.length()   == 1 && Character.isLetter(rowEnd.charAt(0));
        List<String> result = new ArrayList<>();
        if (alphabetic) {
            char from = Character.toUpperCase(rowStart.charAt(0));
            char to   = Character.toUpperCase(rowEnd.charAt(0));
            if (from > to) { char tmp = from; from = to; to = tmp; }   // normalise order
            for (char c = from; c <= to; c++) {
                result.add(String.valueOf(c));
            }
        } else {
            // Numeric strings
            int from = Integer.parseInt(rowStart.strip());
            int to   = Integer.parseInt(rowEnd.strip());
            if (from > to) { int tmp = from; from = to; to = tmp; }
            for (int i = from; i <= to; i++) {
                result.add(String.valueOf(i));
            }
        }
        return result;
    }

    /** Composite key used for duplicate detection. */
    private static String dupKey(String row, String seat) {
        return (row == null ? "" : row) + ":" + seat;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
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

    // ── Stuck-reservation watchdog ───────────────────────────────────────────

    /**
     * Called by {@link com.ticketing.ticket.watchdog.TicketReleaseWatchdog} every minute.
     *
     * <p>Finds every ticket that has been RESERVED for longer than {@code stuckBefore}
     * and releases it back to AVAILABLE. This is a safety net for sagas that crashed
     * at any step without completing the compensation path — even if every other
     * guard (saga watchdog, compensation event) failed, tickets are freed here.
     *
     * <p>Each release is committed in its own transaction (called per-ticket) so that
     * one bad ticket cannot block the rest of the batch.
     */
    @Transactional
    public int releaseStuckReservations(java.time.Instant stuckBefore) {
        List<Ticket> stuck = ticketRepository.findByStatusAndReservedAtBefore(
                TicketStatus.RESERVED, stuckBefore);

        if (stuck.isEmpty()) return 0;

        log.warn("Watchdog: found {} ticket(s) stuck in RESERVED since before {}",
                stuck.size(), stuckBefore);

        for (Ticket ticket : stuck) {
            String orderId = ticket.getLockedByOrderId();
            log.warn("Watchdog releasing: ticketId={} orderId={} reservedAt={}",
                    ticket.getId(), orderId, ticket.getReservedAt());

            ticket.release();
            ticketRepository.save(ticket);
            evictL2(ticket.getId(), ticket.getEventId());

            // Publish TicketReleasedEvent so saga-orchestrator and
            // reservation-service can react (promote waitlist, fail saga).
            eventPublisher.publishReleased(new com.ticketing.common.events.TicketReleasedEvent(
                    null, null,
                    ticket.getId(), orderId,
                    ticket.getEventId(), "WATCHDOG_STUCK_RESERVATION"));
        }

        log.warn("Watchdog released {} stuck reservation(s)", stuck.size());
        return stuck.size();
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
                    cmd.getTicketId(), cmd.getOrderId(), null, ErrorCode.TICKET_LOCK_CONFLICT.name()));
            return;
        }

        try {
            Ticket ticket = ticketRepository.findByIdForUpdate(cmd.getTicketId())
                    .orElse(null);

            if (ticket == null || !ticket.isAvailable()) {
                log.warn("Ticket unavailable id={} saga={}", cmd.getTicketId(), cmd.getSagaId());
                String evtId = ticket != null ? ticket.getEventId() : null;
                eventPublisher.publishReleased(new TicketReleasedEvent(
                        cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(), evtId, ErrorCode.TICKET_UNAVAILABLE.name()));
                return;
            }

            // Validate event is still open for sales
            var event = eventRepository.findById(ticket.getEventId()).orElse(null);
            if (event != null && !event.isOpenForSales()) {
                log.warn("Event {} not open for sales, rejecting saga={}", ticket.getEventId(), cmd.getSagaId());
                eventPublisher.publishReleased(new TicketReleasedEvent(
                        cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(), ticket.getEventId(), ErrorCode.EVENT_NOT_OPEN.name()));
                return;
            }

            ticket.reserve(cmd.getOrderId(), cmd.getUserId(), ticket.getFacePrice());
            ticketRepository.save(ticket);
            evictL2(ticket.getId(), ticket.getEventId());

            eventPublisher.publishReserved(new TicketReservedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    ticket.getId(), cmd.getOrderId(),
                    cmd.getUserId(), ticket.getEventId(), ticket.getLockedPrice()));

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
                      event.getTraceId(), event.getSagaId(), ErrorCode.SAGA_COMPENSATION.name());
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
                traceId, sagaId, ticketId, orderId, ticket.getEventId(), reason));

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
