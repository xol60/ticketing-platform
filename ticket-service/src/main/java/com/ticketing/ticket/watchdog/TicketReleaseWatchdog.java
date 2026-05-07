package com.ticketing.ticket.watchdog;

import com.ticketing.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Last-resort safety net for tickets stuck in RESERVED status.
 *
 * <p>Runs every 60 seconds and releases any ticket whose {@code reservedUntil}
 * deadline has already passed. This fires even if the saga watchdog, compensation
 * events, and Kafka consumers all fail — the only way a ticket can stay stuck
 * beyond its deadline is if this watchdog itself is down.
 *
 * <h3>Why deadline-based, not age-based?</h3>
 * <p>The previous implementation compared {@code reservedAt} against a fixed
 * {@code STUCK_THRESHOLD}. That approach had a race condition: a slow payment
 * gateway (response time > threshold) would cause the watchdog to release the
 * ticket while the payment was still legitimately in flight. The saga would then
 * cancel the order and trigger a refund — even though the payment succeeded.
 *
 * <p>With an explicit {@code reservedUntil} deadline (set by the service to
 * {@code now + RESERVATION_TIMEOUT = 120 s} at reservation time), the watchdog
 * can never fire while the saga has budget left. The deadline is the single
 * source of truth; there is no separate threshold constant here.
 *
 * <h3>Safety guarantee</h3>
 * <pre>
 *   Saga max valid runtime  ≈ 42 s  (30 s price-confirm + 7 s payment + 5 s Kafka)
 *   reservedUntil deadline  = 120 s (3× margin)
 *   Watchdog fires every    = 60 s
 *   Earliest a stuck ticket is released = reservedUntil + 0..60 s = 120..180 s
 * </pre>
 * Sagas that complete normally are always confirmed well before 120 s, so the
 * watchdog never touches them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketReleaseWatchdog {

    private final TicketService ticketService;

    @Scheduled(fixedDelay = 60_000)
    public void releaseStuckReservations() {
        // Pass Instant.now() — the service queries tickets where reservedUntil < now,
        // i.e. tickets whose saga-controlled deadline has expired.
        int released = ticketService.releaseStuckReservations(Instant.now());
        if (released > 0) {
            log.warn("TicketReleaseWatchdog: released {} stuck reservation(s)", released);
        } else {
            log.debug("TicketReleaseWatchdog: no expired reservations found");
        }
    }
}
