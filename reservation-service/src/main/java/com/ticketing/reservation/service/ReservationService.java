package com.ticketing.reservation.service;

import com.ticketing.common.events.ReservationPromotedEvent;
import com.ticketing.common.events.TicketReleasedEvent;
import com.ticketing.reservation.domain.model.Reservation;
import com.ticketing.reservation.domain.model.ReservationStatus;
import com.ticketing.reservation.domain.repository.ReservationRepository;
import com.ticketing.reservation.kafka.ReservationEventPublisher;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String   QUEUE_PREFIX     = "reservation:queue:";
    private static final String   EXCLUSIVE_PREFIX = "ticket:exclusive:";
    private static final long     QUEUE_TTL_HOURS  = 24;

    /**
     * How long a promoted user has to place their order.
     * The Redis exclusive hold TTL is set slightly longer than this so the
     * watchdog always clears the key explicitly rather than relying on natural
     * expiry — this prevents a gap where the ticket is briefly open to everyone
     * between Redis TTL expiry and the watchdog firing.
     */
    private static final Duration EXCLUSIVE_WINDOW    = Duration.ofMinutes(10);
    private static final Duration EXCLUSIVE_REDIS_TTL = EXCLUSIVE_WINDOW.plusSeconds(90);

    /**
     * Maximum number of users allowed in a single ticket's queue.
     *
     * Rationale: with EXCLUSIVE_WINDOW = 10 min, a full queue of depth N means
     * a ticket could be held for up to N × 10 minutes before becoming freely
     * available. Capping at 6 limits the worst-case hold to ~60 minutes.
     *
     * Enforced atomically via a Lua script (ZCARD + ZADD in one round-trip) to
     * prevent concurrent joinQueue() calls from racing past the limit.
     */
    private static final int MAX_QUEUE_DEPTH = 6;

    /**
     * Atomic queue-join script.
     *
     * KEYS[1] — the sorted-set key  (reservation:queue:{ticketId})
     * ARGV[1] — member              (reservationId)
     * ARGV[2] — score               (queuedAt epoch-ms as double string)
     * ARGV[3] — max depth           (MAX_QUEUE_DEPTH as string)
     *
     * Returns 1 if the member was added, 0 if the queue is already full.
     * Using ZADD NX so a duplicate member (e.g. from a retried request that
     * already committed to DB) is silently ignored rather than overwriting.
     */
    private static final RedisScript<Long> JOIN_QUEUE_SCRIPT;
    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
            "local sz = redis.call('ZCARD', KEYS[1]) " +
            "if sz >= tonumber(ARGV[3]) then return 0 end " +
            "redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1]) " +
            "return 1"
        );
        script.setResultType(Long.class);
        JOIN_QUEUE_SCRIPT = script;
    }

    private final ReservationRepository     reservationRepository;
    private final ReservationEventPublisher eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;

    // ── Startup recovery ──────────────────────────────────────────────────────

    /**
     * Rebuilds Redis Sorted Sets from PostgreSQL on startup.
     *
     * Scenario: Redis was restarted (crash, eviction, flush) while PostgreSQL
     * retained all QUEUED reservations. Without recovery, getQueuePosition() returns
     * -1 for every user and promoteNextFromQueue() promotes nobody — the queue is
     * effectively dead until the next ticket release triggers a fresh join.
     *
     * Strategy:
     * - Query all QUEUED records where expiresAt > now, sorted by queuedAt ASC.
     * - Use ZADD NX (addIfAbsent) so entries already present in Redis (partial
     *   survival) are not overwritten — their original score (queuedAt) is kept.
     * - Log a summary: how many entries were restored and across how many tickets.
     *
     * This runs after the Spring context is fully started (ApplicationReadyEvent)
     * so the DataSource and Redis connection pool are both ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void rebuildQueueCache() {
        log.info("Starting Redis queue cache recovery from PostgreSQL...");

        List<Reservation> active = reservationRepository
                .findByStatusAndExpiresAtAfterOrderByQueuedAtAsc(
                        ReservationStatus.QUEUED, Instant.now());

        int restored = 0;
        for (Reservation r : active) {
            String queueKey = QUEUE_PREFIX + r.getTicketId();
            // NX: skip if this member already exists — preserves partial Redis survivors.
            Boolean added = redisTemplate.opsForZSet()
                    .addIfAbsent(queueKey, r.getId().toString(),
                                 (double) r.getQueuedAt().toEpochMilli());
            if (Boolean.TRUE.equals(added)) {
                restored++;
            }
        }

        long distinctTickets = active.stream()
                .map(Reservation::getTicketId)
                .distinct()
                .count();

        log.info("Queue cache recovery complete: {} entries restored across {} ticket queues " +
                 "({} already present in Redis, skipped)",
                 restored, distinctTickets, active.size() - restored);
    }

    // ── Queue operations ──────────────────────────────────────────────────────

    @Transactional
    public Reservation joinQueue(String ticketId, String userId) {
        boolean alreadyQueued = reservationRepository
                .findByUserIdAndTicketIdAndStatus(userId, ticketId, ReservationStatus.QUEUED)
                .isPresent();
        if (alreadyQueued) {
            throw new IllegalStateException("User is already in the queue for ticket: " + ticketId);
        }

        Instant now = Instant.now();
        // Save to DB first so we have a stable UUID to use as the Redis member.
        // If the Lua script rejects the join (queue full), @Transactional rolls back.
        Reservation reservation = Reservation.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(ReservationStatus.QUEUED)
                .queuedAt(now)
                .expiresAt(now.plusSeconds(QUEUE_TTL_HOURS * 3600))
                .version(0L)
                .build();
        reservation = reservationRepository.save(reservation);

        // Atomically check queue depth and add — prevents concurrent callers from
        // racing past MAX_QUEUE_DEPTH between a plain ZCARD and a separate ZADD.
        String queueKey = QUEUE_PREFIX + ticketId;
        Long added = redisTemplate.execute(
                JOIN_QUEUE_SCRIPT,
                Collections.singletonList(queueKey),
                reservation.getId().toString(),
                String.valueOf((double) now.toEpochMilli()),
                String.valueOf(MAX_QUEUE_DEPTH));

        if (added == null || added == 0L) {
            // Queue is full — throw to trigger @Transactional rollback of the DB save.
            throw new IllegalStateException(
                    "Queue is full for ticket " + ticketId +
                    " (max " + MAX_QUEUE_DEPTH + " positions, " +
                    "estimated max wait " + (MAX_QUEUE_DEPTH * EXCLUSIVE_WINDOW.toMinutes()) + " min)");
        }

        log.info("User {} joined queue for ticket {} reservationId={} depth≤{}",
                userId, ticketId, reservation.getId(), MAX_QUEUE_DEPTH);

        // Speculatively try to promote: if this is the first person in an empty queue
        // (no exclusive hold) they get promoted immediately. If someone already holds
        // the exclusive window, doPromoteNext() returns early — safe to call always.
        doPromoteNext(ticketId, null);

        return reservation;
    }

    @Transactional
    public void leaveQueue(String reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(java.util.UUID.fromString(reservationId))
                .orElseThrow(() -> new EntityNotFoundException("Reservation not found: " + reservationId));

        if (!reservation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not the owner of this reservation");
        }
        if (reservation.getStatus() != ReservationStatus.QUEUED) {
            throw new IllegalStateException("Cannot cancel a " + reservation.getStatus() + " reservation");
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        String queueKey = QUEUE_PREFIX + reservation.getTicketId();
        redisTemplate.opsForZSet().remove(queueKey, reservationId);
        log.info("User {} left queue for ticket {} reservationId={}", userId, reservation.getTicketId(), reservationId);
    }

    @Transactional(readOnly = true)
    public long getQueuePosition(String ticketId, String userId) {
        Reservation reservation = reservationRepository
                .findByUserIdAndTicketIdAndStatus(userId, ticketId, ReservationStatus.QUEUED)
                .orElseThrow(() -> new EntityNotFoundException("No active reservation for user in ticket queue"));

        String queueKey = QUEUE_PREFIX + ticketId;
        Long rank = redisTemplate.opsForZSet().rank(queueKey, reservation.getId().toString());
        return rank == null ? -1 : rank + 1; // 1-based position
    }

    /**
     * Called by the Kafka consumer when a ticket is released back to the pool.
     * Delegates to doPromoteNext so the same logic is reused by the watchdog.
     */
    @Transactional
    public void promoteNextFromQueue(TicketReleasedEvent event) {
        doPromoteNext(event.getTicketId(), event.getTraceId());
    }

    /**
     * Watchdog — runs every 60 seconds.
     *
     * Problem being solved: a promoted user who never places an order would stall
     * the queue indefinitely, because the next promotion only fires on a
     * TicketReleasedEvent — which never comes if the promoted user does nothing.
     *
     * For each PROMOTED reservation whose exclusive window has elapsed:
     *   1. Mark it EXPIRED (the user missed their window).
     *   2. Delete the Redis exclusive hold so no stale key lingers.
     *   3. Call doPromoteNext() to give the next person in queue their turn.
     *
     * The queue therefore self-advances until someone buys or the queue drains.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void advanceStalePromotions() {
        List<Reservation> stale = reservationRepository
                .findByStatusAndPromoteExpiresAtBefore(ReservationStatus.PROMOTED, Instant.now());

        for (Reservation r : stale) {
            log.info("Exclusive window elapsed: expiring userId={} ticketId={} reservationId={}",
                    r.getUserId(), r.getTicketId(), r.getId());
            r.setStatus(ReservationStatus.EXPIRED);
            reservationRepository.save(r);

            // Remove stale hold before handing it to the next person
            redisTemplate.delete(EXCLUSIVE_PREFIX + r.getTicketId());

            doPromoteNext(r.getTicketId(), null);
        }

        if (!stale.isEmpty()) {
            log.info("advanceStalePromotions: advanced {} stale promotions", stale.size());
        }
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void expireOldReservations() {
        List<Reservation> expired = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.QUEUED, Instant.now());

        for (Reservation r : expired) {
            r.setStatus(ReservationStatus.EXPIRED);
            reservationRepository.save(r);
            String queueKey = QUEUE_PREFIX + r.getTicketId();
            redisTemplate.opsForZSet().remove(queueKey, r.getId().toString());
            log.info("Expired reservation id={} userId={} ticketId={}", r.getId(), r.getUserId(), r.getTicketId());
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} stale reservations", expired.size());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Core promotion logic — shared by the Kafka listener (ticket released) and
     * the stale-promotion watchdog (exclusive window elapsed).
     *
     * Picks the head of the Redis Sorted Set for the given ticket, removes it,
     * transitions the reservation to PROMOTED, sets promoteExpiresAt, writes the
     * Redis exclusive hold, and publishes ReservationPromotedEvent.
     *
     * If the queue is empty the exclusive hold is cleared so no ghost key lingers.
     */
    private void doPromoteNext(String ticketId, String traceId) {
        String queueKey     = QUEUE_PREFIX + ticketId;
        String exclusiveKey = EXCLUSIVE_PREFIX + ticketId;

        // Guard: if someone is already holding the exclusive window, don't double-promote.
        // This makes doPromoteNext() safe to call speculatively (e.g. right after joinQueue).
        if (Boolean.TRUE.equals(redisTemplate.hasKey(exclusiveKey))) {
            log.debug("Exclusive hold active for ticket={} — skipping promote", ticketId);
            return;
        }

        Set<String> top = redisTemplate.opsForZSet().range(queueKey, 0, 0);
        if (top == null || top.isEmpty()) {
            log.info("Queue empty for ticket={} — clearing exclusive hold if any", ticketId);
            redisTemplate.delete(exclusiveKey);
            return;
        }

        String reservationId = top.iterator().next();
        redisTemplate.opsForZSet().remove(queueKey, reservationId);

        reservationRepository.findById(java.util.UUID.fromString(reservationId)).ifPresent(r -> {
            Instant now = Instant.now();
            r.setStatus(ReservationStatus.PROMOTED);
            r.setPromotedAt(now);
            r.setPromoteExpiresAt(now.plus(EXCLUSIVE_WINDOW));
            reservationRepository.save(r);

            // Overwrite any previous exclusive hold with the new owner.
            // TTL is slightly longer than promoteExpiresAt so the watchdog always
            // removes the key explicitly — prevents a window where the key expires
            // naturally before the watchdog fires and the ticket is briefly open to all.
            redisTemplate.opsForValue().set(
                    EXCLUSIVE_PREFIX + ticketId,
                    r.getUserId(),
                    EXCLUSIVE_REDIS_TTL);

            eventPublisher.publishPromoted(new ReservationPromotedEvent(
                    traceId, null, ticketId, r.getUserId(), reservationId));

            log.info("Promoted userId={} ticketId={} reservationId={} window expires at {}",
                    r.getUserId(), ticketId, reservationId, r.getPromoteExpiresAt());
        });
    }
}
