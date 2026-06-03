package com.ticketing.ticket.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunable knobs for the event-hotness watchdog.
 *
 * <p>Defaults are chosen for a typical ticketing-platform load profile:
 *
 * <ul>
 *   <li><b>enterThreshold = 50 views/min</b> — well above noise floor of a
 *       typical event detail page, easily crossed by a flash-sale opening.
 *   <li><b>exitThreshold = 20 views/min</b> — clearly below the enter
 *       threshold, prevents flapping when traffic hovers near 50.
 *   <li><b>windowSeconds = 60</b> — rolling window for the Redis counter
 *       ({@code event-views:{eventId}} with TTL = window).
 *   <li><b>tickSeconds = 10</b> — watchdog evaluates every 10 s. Fine
 *       enough to mark HOT within a beat of a real surge; cheap on Redis.
 *   <li><b>flagTtlSeconds = 120</b> — safety expiry on the {@code event-hot:*}
 *       Redis flag so a dead watchdog cannot leave events stuck HOT forever.
 * </ul>
 *
 * Override any of these via {@code application.yml} or env without rebuild.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "hotness")
public class HotnessProperties {

    @Positive
    private int enterThreshold = 50;

    @Min(0)
    private int exitThreshold = 20;

    @Positive
    private int windowSeconds = 60;

    @Positive
    private int tickSeconds = 10;

    @Positive
    private int flagTtlSeconds = 120;
}
