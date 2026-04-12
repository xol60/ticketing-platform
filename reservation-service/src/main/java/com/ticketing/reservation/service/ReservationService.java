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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String QUEUE_PREFIX = "reservation:queue:";
    private static final long   QUEUE_TTL_HOURS = 24;

    private final ReservationRepository     reservationRepository;
    private final ReservationEventPublisher eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public Reservation joinQueue(String ticketId, String userId) {
        boolean alreadyQueued = reservationRepository
                .findByUserIdAndTicketIdAndStatus(userId, ticketId, ReservationStatus.QUEUED)
                .isPresent();
        if (alreadyQueued) {
            throw new IllegalStateException("User is already in the queue for ticket: " + ticketId);
        }

        Instant now = Instant.now();
        Reservation reservation = Reservation.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(ReservationStatus.QUEUED)
                .queuedAt(now)
                .expiresAt(now.plusSeconds(QUEUE_TTL_HOURS * 3600))
                .version(0L)
                .build();
        reservation = reservationRepository.save(reservation);

        String queueKey = QUEUE_PREFIX + ticketId;
        redisTemplate.opsForZSet().add(queueKey, reservation.getId().toString(), (double) now.toEpochMilli());
        log.info("User {} joined queue for ticket {} reservationId={}", userId, ticketId, reservation.getId());
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

    @Transactional
    public void promoteNextFromQueue(TicketReleasedEvent event) {
        String ticketId = event.getTicketId();
        String queueKey = QUEUE_PREFIX + ticketId;

        Set<String> top = redisTemplate.opsForZSet().range(queueKey, 0, 0);
        if (top == null || top.isEmpty()) {
            log.info("No one in queue for released ticket={}", ticketId);
            return;
        }

        String reservationId = top.iterator().next();
        redisTemplate.opsForZSet().remove(queueKey, reservationId);

        reservationRepository.findById(java.util.UUID.fromString(reservationId)).ifPresent(r -> {
            r.setStatus(ReservationStatus.PROMOTED);
            r.setPromotedAt(Instant.now());
            reservationRepository.save(r);

            eventPublisher.publishPromoted(new ReservationPromotedEvent(
                    event.getTraceId(), null,
                    ticketId, r.getUserId(), reservationId));

            log.info("Promoted user={} from queue for ticket={} reservationId={}", r.getUserId(), ticketId, reservationId);
        });
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
}
