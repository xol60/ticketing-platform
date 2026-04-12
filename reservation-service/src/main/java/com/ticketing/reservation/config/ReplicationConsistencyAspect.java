package com.ticketing.reservation.config;

import com.ticketing.common.datasource.DataSourceRoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * Ensures read-your-writes consistency after any write transaction.
 *
 * Strategy:
 *  - After a write transaction commits → store "master-sticky:{userId}" in Redis TTL=5s
 *  - On every request start          → check Redis; if key exists set ThreadLocal to MASTER
 *  - After request completes         → clear ThreadLocal
 *
 * This means any read within 5 seconds of a write (even in a different request)
 * is routed to master — covering the replication lag window.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicationConsistencyAspect {

    private static final String STICKY_PREFIX = "master-sticky:";
    private static final Duration STICKY_TTL  = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;

    /**
     * Wraps every @Transactional method.
     * - Before: if user has a sticky-master flag in Redis → force master routing
     * - After write commit: set sticky-master flag in Redis for 5s
     */
    @Around("@annotation(transactional)")
    public Object handleTransactional(ProceedingJoinPoint pjp,
                                      Transactional transactional) throws Throwable {
        String userId = resolveUserId();

        // ── Before execution: check if we must stick to master ──────────────
        if (userId != null && !transactional.readOnly()) {
            // This is a write — force master regardless
            DataSourceRoutingContext.forceMaster();
        } else if (userId != null && isStickyMaster(userId)) {
            // Recent write detected — route this read to master too
            DataSourceRoutingContext.forceMaster();
            log.debug("Sticky-master active for userId={}, routing read to master", userId);
        }

        try {
            Object result = pjp.proceed();

            // ── After successful write commit: mark user as recently written ─
            if (userId != null && !transactional.readOnly()) {
                setStickyMaster(userId);
                log.debug("Write committed for userId={}, sticky-master set for {}s",
                        userId, STICKY_TTL.getSeconds());
            }

            return result;
        } finally {
            DataSourceRoutingContext.clear();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isStickyMaster(String userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(STICKY_PREFIX + userId));
        } catch (Exception e) {
            log.warn("Redis unavailable for sticky-master check userId={}, defaulting to master", userId);
            return true; // fail safe → master
        }
    }

    private void setStickyMaster(String userId) {
        try {
            redisTemplate.opsForValue().set(STICKY_PREFIX + userId, "1", STICKY_TTL);
        } catch (Exception e) {
            log.warn("Failed to set sticky-master in Redis for userId={}", userId);
        }
    }

    /**
     * Resolves userId from:
     *  1. X-User-Id request header (injected by gateway for authenticated requests)
     *  2. null if no active web request (e.g. scheduled jobs)
     */
    private String resolveUserId() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return attrs.getRequest().getHeader("X-User-Id");
        } catch (Exception e) {
            return null;
        }
    }
}
