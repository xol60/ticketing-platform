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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    /**
     * How long a saga may legitimately hold a ticket in RESERVED status.
     * Must cover the worst-case saga path:
     *   30 s  price-confirm window
     * +  7 s  payment retries (3×)
     * +  5 s  Kafka hops and processing
     * = ~42 s actual max
     *
     * We set 120 s (3× margin) to absorb slow payment gateways, GC pauses,
     * and any other delays while still releasing truly stuck tickets quickly.
     * The watchdog releases a ticket only AFTER ticket.reservedUntil passes,
     * so this constant is the single source of truth for the safety window.
     */
    private static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(120);

    private final TicketRepository      ticketRepository;
    private final TicketMapper          ticketMapper;
    private final TicketEventPublisher  eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;
    private final EventRepository        eventRepository;
    private final ObjectMapper          objectMapper;
    private final TransactionTemplate   txTemplate;

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
     * Called by {@link com.ticketing.ticket.watchdog.TicketReleaseWatchdog} every 60 s.
     *
     * <p>Releases every RESERVED ticket whose explicit {@code reservedUntil} deadline
     * has already passed. Because the deadline is set to
     * {@code now + RESERVATION_TIMEOUT (120 s)} at reservation time, this watchdog
     * can never fire while a saga is still legitimately running — only truly orphaned
     * tickets (saga crashed at every level, including the saga-orchestrator's own
     * watchdog) are released here.
     *
     * <p>Contrast with the previous age-based approach ({@code reservedAt < now - threshold}):
     * that could release a ticket while payment was still in flight if the payment
     * gateway was slow (>60 s), causing an unnecessary refund of a successful charge.
     */
    @Transactional
    public int releaseStuckReservations(java.time.Instant now) {
        List<Ticket> stuck = ticketRepository.findByStatusAndReservedUntilBefore(
                TicketStatus.RESERVED, now);

        if (stuck.isEmpty()) return 0;

        log.warn("Watchdog: found {} ticket(s) past their reservedUntil deadline (now={})",
                stuck.size(), now);

        for (Ticket ticket : stuck) {
            String orderId = ticket.getLockedByOrderId();
            log.warn("Watchdog releasing: ticketId={} orderId={} reservedUntil={}",
                    ticket.getId(), orderId, ticket.getReservedUntil());

            ticket.release();
            ticketRepository.save(ticket);
            evictL2(ticket.getId(), ticket.getEventId());

            // Publish TicketReleasedEvent so the saga-orchestrator can react:
            // mark order as FAILED and trigger any compensation (e.g. refund if
            // payment somehow already completed before the saga timed out).
            //
            // IMPORTANT: pass orderId as sagaId — in this system sagaId == orderId.
            // Without this, onTicketReleased() in the saga orchestrator does
            // loadOrWarn(null) → returns null immediately and the payment cancel
            // command is never sent, leaving the customer charged with no ticket.
            eventPublisher.publishReleased(new com.ticketing.common.events.TicketReleasedEvent(
                    null, orderId,          // sagaId = orderId (they are equivalent)
                    ticket.getId(), orderId,
                    ticket.getEventId(), "WATCHDOG_STUCK_RESERVATION"));
        }

        log.warn("Watchdog released {} stuck reservation(s)", stuck.size());
        return stuck.size();
    }

    // ── Saga command handlers ────────────────────────────────────────────────

    /**
     * Reserve a ticket for an order.
     *
     * <h3>Two-layer non-blocking concurrency control</h3>
     * <ol>
     *   <li><b>Redis SETNX</b> — fast-fail under contention without touching the DB.
     *       Two pods racing for the same ticket: one wins, the other returns in &lt;1 ms with
     *       {@link ErrorCode#TICKET_LOCK_CONFLICT}.</li>
     *   <li><b>JPA {@code @Version}</b> — non-blocking safety net. If a writer somehow
     *       slips past the Redis gate (Redis outage, expired key mid-flight, direct SQL),
     *       the optimistic check on {@code saveAndFlush} fires
     *       {@link OptimisticLockingFailureException}, the transaction rolls back, and we
     *       publish {@link ErrorCode#TICKET_UNAVAILABLE} so the saga compensates.</li>
     * </ol>
     *
     * <p>We deliberately do <em>not</em> use {@code SELECT ... FOR UPDATE} (pessimistic
     * row lock) here. The Kafka consumer concurrency is 10 (matching the partition count);
     * a single pessimistic lock-waiter would tie up 1/10 of the thread pool indefinitely.
     * Both Redis SETNX and {@code @Version} return immediately, so the consumer thread is
     * never blocked.
     *
     * <h3>Tight transactional scope</h3>
     * No {@code @Transactional} on the method — the DB write runs inside a
     * {@link TransactionTemplate} block. External I/O (Redis SETNX, Kafka publish via
     * the hybrid-outbox publisher) runs <em>outside</em> the tx, so a DB connection is
     * held only for the actual UPDATE (~5 ms).
     */
    public void handleReserveCommand(TicketReserveCommand cmd) {
        String lockKey = LOCK_PREFIX + cmd.getTicketId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, cmd.getSagaId(), LOCK_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            // ── Idempotency check #1 — Kafka may have redelivered while the first
            //   delivery is still in flight. SETNX stores cmd.getSagaId() as the value,
            //   so if the current lock holder matches our saga, we're a duplicate of an
            //   in-flight first delivery — silently skip; the first delivery will publish.
            String currentHolder = safeGet(lockKey);
            if (cmd.getSagaId().equals(currentHolder)) {
                log.info("Redelivered ReserveCommand while same saga is still processing — skipping silently sagaId={}",
                        cmd.getSagaId());
                return;
            }
            log.warn("Could not acquire lock for ticket={} saga={} (held by {})",
                    cmd.getTicketId(), cmd.getSagaId(), currentHolder);
            eventPublisher.publishReleased(new TicketReleasedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), null, ErrorCode.TICKET_LOCK_CONFLICT.name()));
            return;
        }

        try {
            // ── Tight DB transaction — read + validate + UPDATE only ──────
            ReserveOutcome outcome = txTemplate.execute(status -> {
                Ticket ticket = ticketRepository.findById(cmd.getTicketId()).orElse(null);

                // ── Idempotency check #2 — Kafka redelivery after the first delivery
                //   already committed but the consumer crashed before sending the manual
                //   ack. The ticket is RESERVED and owned by THIS order — return success
                //   so the post-commit block republishes TicketReservedEvent. Saga sees
                //   the duplicate event and ignores it (it's already past TICKET_RESERVED).
                if (ticket != null
                        && ticket.getStatus() == TicketStatus.RESERVED
                        && cmd.getOrderId().equals(ticket.getLockedByOrderId())) {
                    log.info("Ticket already reserved by this order (idempotent retry) id={} order={}",
                            cmd.getTicketId(), cmd.getOrderId());
                    return ReserveOutcome.idempotentRetry(
                            ticket.getEventId(), ticket.getLockedPrice(), ticket.getReservedUntil());
                }

                if (ticket == null || !ticket.isAvailable()) {
                    String evtId = ticket != null ? ticket.getEventId() : null;
                    return ReserveOutcome.fail(evtId, ErrorCode.TICKET_UNAVAILABLE.name());
                }

                var event = eventRepository.findById(ticket.getEventId()).orElse(null);
                if (event != null && !event.isOpenForSales()) {
                    return ReserveOutcome.fail(ticket.getEventId(), ErrorCode.EVENT_NOT_OPEN.name());
                }

                java.time.Instant deadline = java.time.Instant.now().plus(RESERVATION_TIMEOUT);
                ticket.reserve(cmd.getOrderId(), cmd.getUserId(), ticket.getFacePrice(), deadline);

                try {
                    ticketRepository.saveAndFlush(ticket);    // ← @Version check fires here
                } catch (OptimisticLockingFailureException e) {
                    log.warn("Optimistic lock conflict on reserve ticket={} saga={}: {}",
                            cmd.getTicketId(), cmd.getSagaId(), e.getMessage());
                    return ReserveOutcome.fail(ticket.getEventId(), ErrorCode.TICKET_UNAVAILABLE.name());
                }
                return ReserveOutcome.success(ticket.getEventId(), ticket.getLockedPrice(), deadline);
            });

            // ── After tx commits — cache evict + Kafka publish (outside tx) ──
            if (outcome.success) {
                if (!outcome.idempotentRetry) {
                    // Real first-time write — invalidate caches so subsequent reads see fresh state
                    evictL2(cmd.getTicketId(), outcome.eventId);
                }
                eventPublisher.publishReserved(new TicketReservedEvent(
                        cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(),
                        cmd.getUserId(), outcome.eventId, outcome.lockedPrice));
                if (outcome.idempotentRetry) {
                    log.info("Republished TicketReservedEvent for idempotent retry sagaId={} order={}",
                            cmd.getSagaId(), cmd.getOrderId());
                } else {
                    log.info("Ticket reserved id={} order={} reservedUntil={}",
                            cmd.getTicketId(), cmd.getOrderId(), outcome.deadline);
                }
            } else {
                log.warn("Reserve failed ticket={} saga={} reason={}",
                        cmd.getTicketId(), cmd.getSagaId(), outcome.failureReason);
                eventPublisher.publishReleased(new TicketReleasedEvent(
                        cmd.getTraceId(), cmd.getSagaId(),
                        cmd.getTicketId(), cmd.getOrderId(),
                        outcome.eventId, outcome.failureReason));
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /** Defensive Redis read — never throws (the value is informational, not authoritative). */
    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Outcome envelope used to ferry tx result to the post-commit publish block.
     *
     * <p>{@code idempotentRetry} distinguishes a fresh successful reservation from a
     * Kafka-redelivery retry where the ticket was already reserved by this same order.
     * The post-commit block uses this to choose the right log message and decide whether
     * to invalidate caches (no need on a retry — caches were already invalidated by the
     * original successful delivery).
     */
    private record ReserveOutcome(boolean success, boolean idempotentRetry, String eventId,
                                   java.math.BigDecimal lockedPrice,
                                   java.time.Instant deadline,
                                   String failureReason) {
        static ReserveOutcome success(String eventId, java.math.BigDecimal lockedPrice, java.time.Instant deadline) {
            return new ReserveOutcome(true, false, eventId, lockedPrice, deadline, null);
        }
        static ReserveOutcome idempotentRetry(String eventId, java.math.BigDecimal lockedPrice, java.time.Instant deadline) {
            return new ReserveOutcome(true, true, eventId, lockedPrice, deadline, null);
        }
        static ReserveOutcome fail(String eventId, String reason) {
            return new ReserveOutcome(false, false, eventId, null, null, reason);
        }
    }

    /**
     * Confirm a reserved ticket as part of saga finalization.
     *
     * <p>Uses {@code @Version} optimistic locking — no pessimistic row lock. The only
     * realistic race is with the reservation watchdog releasing an expired reservation
     * concurrently. If that happens, our {@code saveAndFlush} throws
     * {@link OptimisticLockingFailureException}; we treat it as "ticket no longer in the
     * state we expected" and publish a release event so the saga can compensate.
     */
    public void handleConfirmCommand(TicketConfirmCommand cmd) {
        // ── Tight DB transaction — read + status check + UPDATE only ──────
        ConfirmOutcome outcome = txTemplate.execute(status -> {
            Ticket ticket = ticketRepository.findById(cmd.getTicketId()).orElse(null);

            // Idempotency: already confirmed by THIS order — Kafka may have re-delivered
            if (ticket != null
                    && ticket.getStatus() == TicketStatus.CONFIRMED
                    && cmd.getOrderId().equals(ticket.getLockedByOrderId())) {
                return ConfirmOutcome.idempotent(ticket.getEventId(), ticket.getLockedByUserId());
            }

            // Cannot confirm: ticket gone, wrong status, or owned by another order
            // (e.g. watchdog already released it after reservedUntil passed)
            if (ticket == null
                    || ticket.getStatus() != TicketStatus.RESERVED
                    || !cmd.getOrderId().equals(ticket.getLockedByOrderId())) {
                return ConfirmOutcome.fail(ticket != null ? ticket.getEventId() : null,
                        ticket == null ? "NOT_FOUND" : ticket.getStatus().name());
            }

            ticket.confirm(cmd.getOrderId());
            try {
                ticketRepository.saveAndFlush(ticket);    // ← @Version check fires here
            } catch (OptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict on confirm ticket={} saga={}: {}",
                        cmd.getTicketId(), cmd.getSagaId(), e.getMessage());
                return ConfirmOutcome.fail(ticket.getEventId(), "OPTIMISTIC_LOCK");
            }
            return ConfirmOutcome.success(ticket.getEventId(), ticket.getLockedByUserId());
        });

        // ── After tx commits — cache evict + Kafka publish (outside tx) ──
        if (outcome.success || outcome.idempotent) {
            if (outcome.success) {
                evictL2(cmd.getTicketId(), outcome.eventId);
                log.info("Ticket confirmed id={} order={}", cmd.getTicketId(), cmd.getOrderId());
            } else {
                log.info("Ticket already confirmed (idempotent retry) id={} order={}",
                        cmd.getTicketId(), cmd.getOrderId());
            }
            eventPublisher.publishConfirmed(new TicketConfirmedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), outcome.userId));
        } else {
            log.warn("Cannot confirm ticket id={} reason={} — publishing failure so saga compensates",
                    cmd.getTicketId(), outcome.failureReason);
            // We MUST publish a failure event here — silently returning would leave the
            // saga waiting forever and the payment that already succeeded would never refund.
            eventPublisher.publishReleased(new TicketReleasedEvent(
                    cmd.getTraceId(), cmd.getSagaId(),
                    cmd.getTicketId(), cmd.getOrderId(), outcome.eventId,
                    "CONFIRM_FAILED_WRONG_STATE"));
        }
    }

    /** Outcome envelope for confirm operations. */
    private record ConfirmOutcome(boolean success, boolean idempotent,
                                   String eventId, String userId, String failureReason) {
        static ConfirmOutcome success(String eventId, String userId) {
            return new ConfirmOutcome(true, false, eventId, userId, null);
        }
        static ConfirmOutcome idempotent(String eventId, String userId) {
            return new ConfirmOutcome(false, true, eventId, userId, null);
        }
        static ConfirmOutcome fail(String eventId, String reason) {
            return new ConfirmOutcome(false, false, eventId, null, reason);
        }
    }

    public void handleReleaseCommand(TicketReleaseCommand cmd) {
        releaseTicket(cmd.getTicketId(), cmd.getOrderId(),
                      cmd.getTraceId(), cmd.getSagaId(), cmd.getReason());
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

    /**
     * Release a reserved ticket. Used by saga compensation and the reservation watchdog.
     *
     * <p>Uses {@code @Version} optimistic locking. Release is naturally idempotent: the
     * goal is "make this ticket not RESERVED by this order." If another writer (e.g. the
     * watchdog) already released or repurposed the ticket, we treat that as success and
     * skip publishing a duplicate event. DB tx is scoped tightly via {@link TransactionTemplate}
     * so the Kafka publish happens after commit.
     */
    private void releaseTicket(String ticketId, String orderId,
                                String traceId, String sagaId, String reason) {
        ReleaseOutcome outcome = txTemplate.execute(status -> {
            Ticket ticket = ticketRepository.findById(ticketId).orElse(null);

            if (ticket == null) {
                return ReleaseOutcome.skipped("NOT_FOUND", null);
            }
            if (ticket.getStatus() != TicketStatus.RESERVED
                    || !orderId.equals(ticket.getLockedByOrderId())) {
                return ReleaseOutcome.skipped("STATE_MISMATCH:" + ticket.getStatus(), ticket.getEventId());
            }

            ticket.release();
            try {
                ticketRepository.saveAndFlush(ticket);    // ← @Version check fires here
            } catch (OptimisticLockingFailureException e) {
                // Concurrent writer already changed the row. Release goal achieved either
                // way (ticket is no longer RESERVED by this order). Idempotent.
                return ReleaseOutcome.skipped("OPTIMISTIC_LOCK", ticket.getEventId());
            }
            return ReleaseOutcome.released(ticket.getEventId());
        });

        if (outcome.released) {
            evictL2(ticketId, outcome.eventId);
            eventPublisher.publishReleased(new TicketReleasedEvent(
                    traceId, sagaId, ticketId, orderId, outcome.eventId, reason));
            log.info("Ticket released id={} order={} reason={}", ticketId, orderId, reason);
        } else {
            log.info("Release skipped: ticket={} order={} reason={}",
                    ticketId, orderId, outcome.skipReason);
        }
    }

    /** Outcome envelope for release operations. */
    private record ReleaseOutcome(boolean released, String eventId, String skipReason) {
        static ReleaseOutcome released(String eventId)              { return new ReleaseOutcome(true,  eventId, null); }
        static ReleaseOutcome skipped(String reason, String evtId)  { return new ReleaseOutcome(false, evtId,   reason); }
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
