package com.ticketing.common.idempotency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for {@link IdempotencyFilter}.
 *
 * <p>Services opt in by setting {@code idempotency.enabled=true} in their
 * {@code application.yml} (or via env var {@code IDEMPOTENCY_ENABLED}) and
 * listing the URL prefixes that should be deduped. Anything else passes
 * through untouched.
 *
 * <p>Example:
 * <pre>
 * idempotency:
 *   enabled: true
 *   paths:
 *     - /api/orders
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /** Master switch — when false, the filter is a no-op. */
    private boolean enabled = false;

    /**
     * URL path prefixes that participate in idempotency.
     * Match is {@code request.getRequestURI().startsWith(prefix)} AND the
     * HTTP method is POST. GETs and idempotent-by-definition verbs are
     * always passed through.
     */
    private List<String> paths = List.of();

    /**
     * How long the cached response is kept in Redis. 24h matches Stripe's
     * documented behaviour and is long enough to cover the longest realistic
     * "user came back after lunch and clicked Buy again" window without
     * filling Redis with dead entries.
     */
    private Duration ttl = Duration.ofHours(24);

    /**
     * Body-size ceiling for which we bother hashing. Larger requests bypass
     * the body-hash check (and therefore can't dedup). 1 MB lines up with
     * the nginx body-size cap; setting this higher than nginx allows would
     * be meaningless.
     */
    private int maxBodyBytes = 1_048_576;
}
