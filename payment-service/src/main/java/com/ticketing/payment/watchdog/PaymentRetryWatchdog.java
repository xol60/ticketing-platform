package com.ticketing.payment.watchdog;

import com.ticketing.payment.domain.model.Payment;
import com.ticketing.payment.domain.repository.PaymentRepository;
import com.ticketing.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls for PENDING payments that are due for a gateway charge attempt and
 * dispatches them to {@link PaymentService#attemptRetry}.
 *
 * <h3>Why a watchdog instead of blocking in the consumer thread?</h3>
 * The previous design called {@code gateway.charge()} and {@code Thread.sleep()}
 * directly inside the Kafka consumer thread:
 * <ul>
 *   <li>The consumer thread was blocked for 1–7 seconds per failed payment.</li>
 *   <li>The entire Kafka partition was frozen — no other {@code PaymentCommand}
 *       (including {@code PaymentCancelCommand} for other orders) could be
 *       processed until the sleep finished.</li>
 *   <li>With all 3 consumer threads sleeping simultaneously, the entire
 *       {@code payment.cmd} topic stalled for several seconds.</li>
 * </ul>
 *
 * <h3>New model</h3>
 * <ol>
 *   <li>Consumer thread: saves {@code Payment{PENDING, nextRetryAt=now}} and
 *       returns immediately — ACK is sent, the thread polls the next message.</li>
 *   <li>This watchdog runs every 2 seconds on a dedicated scheduler thread,
 *       completely independent of the Kafka consumer threads.</li>
 *   <li>{@link PaymentService#attemptRetry} handles the gateway call and
 *       optimistic-locking-based claim to prevent double-charging across pods.</li>
 * </ol>
 *
 * <h3>Throughput</h3>
 * Consumer threads are no longer blocked by gateway latency, so they can
 * process ~300 {@code PaymentCommand} messages per second instead of ~15.
 * The retry throughput is bounded by the scheduler tick (2 s) and the batch
 * size (50 per tick), which is ample for typical load.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRetryWatchdog {

    /** Max payments processed per watchdog tick — prevents gateway overload on restart. */
    private static final int BATCH_SIZE = 50;

    private final PaymentRepository paymentRepository;
    private final PaymentService    paymentService;

    /**
     * Runs every 2 seconds.  Fetches PENDING payments whose {@code nextRetryAt}
     * is at or before the current time and dispatches each to
     * {@link PaymentService#attemptRetry}.
     *
     * <p>The query hits {@code idx_payments_retry_due} (partial index on PENDING rows),
     * so it is essentially free when there are no payments due.
     *
     * <p>{@code fixedDelay} (not {@code fixedRate}) ensures ticks don't overlap:
     * the next tick starts 2 s <em>after</em> the current tick finishes, so a
     * large batch doesn't cause concurrent ticks to pile up.
     */
    @Scheduled(fixedDelay = 2_000)
    public void retryDuePayments() {
        Instant now = Instant.now();
        List<Payment> due = paymentRepository.findDueForRetry(now, PageRequest.of(0, BATCH_SIZE));

        if (due.isEmpty()) return;

        log.info("[PaymentRetryWatchdog] {} payment(s) due for retry", due.size());

        for (Payment payment : due) {
            try {
                paymentService.attemptRetry(payment.getId());
            } catch (Exception ex) {
                // Never let one payment failure abort the rest of the batch.
                log.error("[PaymentRetryWatchdog] Unexpected error retrying paymentId={} orderId={}: {}",
                        payment.getId(), payment.getOrderId(), ex.getMessage(), ex);
            }
        }
    }
}
