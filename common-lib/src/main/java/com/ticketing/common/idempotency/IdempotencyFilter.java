package com.ticketing.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HTTP idempotency for POST endpoints — the "Stripe pattern".
 *
 * <h2>What it does</h2>
 *
 * <p>For each configured path + POST method combo, the filter checks the
 * {@code Idempotency-Key} header (paired with {@code X-User-Id} injected
 * by the gateway) against a Redis-backed dedup table:
 *
 * <ul>
 *   <li><b>Cache miss</b> → request runs, response (status + body) is
 *       stored under {@code idem:{userId}:{key}} for {@link IdempotencyProperties#getTtl()}.</li>
 *   <li><b>Cache hit, same body hash</b> → cached response replayed
 *       verbatim. The downstream controller is NOT invoked, so no
 *       database write / Kafka publish happens. This is the core
 *       "exactly-once HTTP" guarantee.</li>
 *   <li><b>Cache hit, different body hash</b> → 422 Unprocessable
 *       Entity with errorCode {@code IDEMPOTENCY_KEY_REUSED}.
 *       Protects against a buggy or malicious client reusing one
 *       user's key across different intents.</li>
 *   <li><b>Missing key or user</b> → pass through unchanged. The header
 *       is optional; clients that don't send it get no idempotency
 *       protection. This keeps backward compatibility for any caller
 *       that hasn't been updated yet.</li>
 * </ul>
 *
 * <h2>Per-user scope is the security model</h2>
 *
 * Keys are namespaced by {@code userId}, not global. Two different users
 * can use identical keys with zero interference. A malicious client
 * cannot collide with another user's pending key — the worst they can
 * do is collide with their own, which is harmless.
 *
 * <h2>Fail-open on Redis</h2>
 *
 * If Redis is unreachable we log a WARN and pass the request through
 * uncached. The reasoning: a Redis outage shouldn't fail checkout. The
 * worst case is one extra duplicate per window per affected user; the
 * alternative is rejecting every checkout while Redis is down, which is
 * a much worse outcome. A "fail-open" counter would belong here once
 * the metrics story is built out.
 *
 * <h2>Filter order</h2>
 *
 * Runs BEFORE the controller but AFTER any gateway-injected auth
 * headers reach the request — which is automatic in the servlet stack
 * because gateway injection happens at network ingress. We pin a high
 * order value so this filter sits late in the chain, after CORS /
 * tracing filters.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE - 10)  // late, but before the dispatcher servlet
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final IdempotencyProperties props;
    private final ObjectMapper         mapper;

    static final String HEADER_KEY  = "Idempotency-Key";
    static final String HEADER_USER = "X-User-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only POSTs to configured paths participate.
        if (!HttpMethod.POST.matches(request.getMethod())) return true;
        String uri = request.getRequestURI();
        return props.getPaths().stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String key    = request.getHeader(HEADER_KEY);
        String userId = request.getHeader(HEADER_USER);

        // Header is optional — backward-compatible no-op when absent.
        if (isBlank(key) || isBlank(userId)) {
            chain.doFilter(request, response);
            return;
        }

        // Eager-buffer the body so we can hash it AND let the controller read it.
        CachedBodyHttpServletRequest cachedReq = new CachedBodyHttpServletRequest(request);
        byte[] body = cachedReq.getCachedBody();

        // Refuse to hash absurdly large bodies — pass through without dedup.
        // (nginx already caps at 1 MB upstream, this is belt-and-braces.)
        if (body.length > props.getMaxBodyBytes()) {
            log.warn("Body exceeds idempotency body cap ({} > {} bytes) — passing through uncached",
                    body.length, props.getMaxBodyBytes());
            chain.doFilter(cachedReq, response);
            return;
        }

        String bodyHash = sha256Hex(body);
        String redisKey = "idem:" + userId + ":" + key;

        // ── 1. Dedup lookup ──────────────────────────────────────────────
        StoredResponse cached;
        try {
            String json = redis.opsForValue().get(redisKey);
            cached = (json == null) ? null : mapper.readValue(json, StoredResponse.class);
        } catch (Exception e) {
            // Redis unreachable or stored JSON unparseable. Fail open — the
            // worst case is one duplicate, the alternative is failing every
            // checkout while Redis is down.
            log.warn("Idempotency cache lookup failed for key={} userId={}: {} — falling through",
                    key, userId, e.getMessage());
            chain.doFilter(cachedReq, response);
            return;
        }

        // ── 2a. Same-key, different-body → 422 ───────────────────────────
        if (cached != null && !cached.bodyHash().equals(bodyHash)) {
            log.warn("Idempotency-Key reused with different body. userId={} key={}", userId, key);
            writeJson(response, 422,
                    "{\"success\":false,\"errorCode\":\"IDEMPOTENCY_KEY_REUSED\","
                            + "\"message\":\"Idempotency-Key was reused with a different request body\"}");
            return;
        }

        // ── 2b. Same-key, same-body → replay cached response ─────────────
        if (cached != null) {
            log.debug("Idempotency hit: replaying cached response. userId={} key={} status={}",
                    userId, key, cached.status());
            writeJson(response, cached.status(), cached.body());
            return;
        }

        // ── 3. Miss → run the controller and capture the response ────────
        ContentCachingResponseWrapper wrappedResp = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedReq, wrappedResp);
        } finally {
            // Always copy the captured body back to the real response so the
            // client sees it, regardless of whether we successfully cache.
            int status = wrappedResp.getStatus();
            byte[] respBody = wrappedResp.getContentAsByteArray();
            wrappedResp.copyBodyToResponse();

            // Only cache 2xx — failures shouldn't latch (the user may
            // legitimately retry with corrected input and a fresh key, OR
            // the same key after the failure window). Server errors (5xx)
            // are even more obviously not-cacheable.
            if (status >= 200 && status < 300) {
                try {
                    StoredResponse toStore = new StoredResponse(
                            status,
                            new String(respBody, StandardCharsets.UTF_8),
                            bodyHash);
                    redis.opsForValue().set(redisKey, mapper.writeValueAsString(toStore), props.getTtl());
                } catch (Exception e) {
                    log.warn("Failed to write idempotency entry for key={} userId={}: {}",
                            key, userId, e.getMessage());
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE — should never happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    /** Persisted envelope. Public for tests + ObjectMapper visibility. */
    public record StoredResponse(int status, String body, String bodyHash) {}
}
