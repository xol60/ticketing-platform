package com.ticketing.ticket.watchdog;

import com.ticketing.ticket.config.HotnessProperties;
import com.ticketing.ticket.kafka.EventHotnessPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Background tick that promotes / demotes events between HOT and not-HOT
 * based on a rolling-window Redis view counter.
 *
 * <h3>How it works</h3>
 * <ul>
 *   <li>Every event detail page hit does
 *       {@code INCR event-views:{eventId}} + {@code EXPIRE … = windowSeconds}
 *       (done in {@code TicketService.listAvailableTicketsByEvent}).
 *   <li>Every {@code tickSeconds} this watchdog SCANs all
 *       {@code event-views:*} keys and reads their counts.
 *   <li>If count ≥ {@code enterThreshold} and the event is not currently
 *       flagged HOT → SET {@code event-hot:{eventId}} with TTL =
 *       {@code flagTtlSeconds}, publish {@code hot=true}.
 *   <li>If count ≤ {@code exitThreshold} and the event IS flagged HOT →
 *       DEL the flag, publish {@code hot=false}.
 * </ul>
 *
 * <p>Hysteresis (enter &gt; exit) prevents flapping when traffic hovers near
 * the threshold. The TTL on the HOT flag is a safety net — if this watchdog
 * dies, every flag self-expires within {@code flagTtlSeconds}.
 *
 * <h3>Multi-pod story</h3>
 * The counter and the flag both live in Redis, so all ticket-service pods
 * observe the same state. If multiple ticket-service pods race to write the
 * flag, the last writer wins — harmless because the value (presence) is
 * identical and the publish dedupes via the Kafka consumer's idempotent
 * downstream effect (log a transition).
 *
 * <p>To avoid TWO pods publishing the same transition, we only publish when
 * the local "is currently hot" judgment differs from the prior state read
 * from Redis. A simultaneous double-publish is rare (10 s tick + millisecond
 * Redis race window) and idempotent at the consumer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventHotnessWatchdog {

    private static final String VIEWS_PREFIX = "event-views:";
    private static final String HOT_PREFIX   = "event-hot:";
    /** Cap the SCAN batch so a degenerate run on a huge Redis doesn't stall the tick. */
    private static final int    SCAN_BATCH   = 256;
    /** Cap on events considered per tick — protects the watchdog under unusually high cardinality. */
    private static final int    MAX_KEYS_PER_TICK = 5_000;

    private final StringRedisTemplate    redisTemplate;
    private final EventHotnessPublisher  publisher;
    private final HotnessProperties      props;

    /** Disabled in tests where the @Scheduled tick is noise. */
    @Value("${hotness.watchdog-enabled:true}")
    private boolean watchdogEnabled;

    @Scheduled(fixedDelayString = "${hotness.tick-seconds:10}000")
    public void tick() {
        if (!watchdogEnabled) return;

        // ── 1. Collect view-counter keys via SCAN (NOT KEYS — non-blocking) ──
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(VIEWS_PREFIX + "*").count(SCAN_BATCH).build())) {
            while (cursor.hasNext() && keys.size() < MAX_KEYS_PER_TICK) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("HotnessWatchdog: Redis SCAN failed: {}", e.getMessage());
            return;
        }
        if (keys.isEmpty()) return;

        // ── 2. Bulk-read counters via MGET (one Redis round-trip instead of N) ──
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) return;

        int promoted = 0, demoted = 0;
        for (int i = 0; i < keys.size(); i++) {
            String key   = keys.get(i);
            String value = values.get(i);
            if (value == null) continue;   // expired between SCAN and MGET

            long count;
            try {
                count = Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("HotnessWatchdog: non-numeric value for key={}: {}", key, value);
                continue;
            }

            String eventId = key.substring(VIEWS_PREFIX.length());
            String hotKey  = HOT_PREFIX + eventId;
            boolean currentlyHot = Boolean.TRUE.equals(redisTemplate.hasKey(hotKey));

            // ── Promotion: count crossed enter threshold while not hot ──
            if (count >= props.getEnterThreshold() && !currentlyHot) {
                redisTemplate.opsForValue().set(hotKey, "1",
                        Duration.ofSeconds(props.getFlagTtlSeconds()));
                publisher.publishTransition(eventId, true, count);
                promoted++;
            }
            // ── Demotion: count fell below exit threshold while hot ──
            else if (count <= props.getExitThreshold() && currentlyHot) {
                redisTemplate.delete(hotKey);
                publisher.publishTransition(eventId, false, count);
                demoted++;
            }
            // ── Stay hot: refresh TTL so a still-hot event doesn't drop on TTL ──
            else if (count >= props.getExitThreshold() && currentlyHot) {
                redisTemplate.expire(hotKey, Duration.ofSeconds(props.getFlagTtlSeconds()));
            }
        }

        if (promoted > 0 || demoted > 0) {
            log.info("HotnessWatchdog tick: {} keys scanned, {} promoted, {} demoted",
                    keys.size(), promoted, demoted);
        }
    }
}
