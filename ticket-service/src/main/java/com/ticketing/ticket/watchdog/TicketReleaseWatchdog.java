package com.ticketing.ticket.watchdog;

import com.ticketing.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Last-resort safety net for tickets stuck in RESERVED status.
 *
 * <p>Runs every 60 seconds and releases any ticket that has been RESERVED for
 * longer than {@link #STUCK_THRESHOLD}. This fires even if the saga watchdog,
 * compensation events, and Kafka consumers all fail — the only way a ticket
 * can stay stuck beyond the threshold is if this watchdog itself is down.
 *
 * <p>Threshold rationale (flash-sale settings):
 * <ul>
 *   <li>Price-change confirmation window: 30 s</li>
 *   <li>Payment retries (3×): ~7 s</li>
 *   <li>Kafka hops + processing: ~5 s</li>
 *   <li>Total max valid reservation: ~45 s</li>
 * </ul>
 * A 60-second threshold gives a comfortable margin above the longest valid
 * saga while still releasing stuck tickets within one watchdog cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketReleaseWatchdog {

    /** Any RESERVED ticket older than this is considered stuck. */
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(1);

    private final TicketService ticketService;

    @Scheduled(fixedDelay = 60_000)
    public void releaseStuckReservations() {
        Instant threshold = Instant.now().minus(STUCK_THRESHOLD);
        int released = ticketService.releaseStuckReservations(threshold);
        if (released > 0) {
            log.warn("TicketReleaseWatchdog: released {} stuck reservation(s)", released);
        } else {
            log.debug("TicketReleaseWatchdog: no stuck reservations found");
        }
    }
}
