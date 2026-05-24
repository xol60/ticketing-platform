package com.ticketing.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural contract for {@link IdempotencyFilter}.
 *
 * <p>Five paths under test, in order of "most likely to break the system
 * if it regresses":
 *
 * <ol>
 *   <li><b>Hit replays cached response</b> — the whole point of the filter.
 *       If this breaks we lose dedup and customers get double-charged.</li>
 *   <li><b>Same key + different body → 422</b> — protects against a malicious
 *       or buggy client reusing one user's key for a different intent.</li>
 *   <li><b>Miss runs chain + caches 2xx response</b> — fresh request flows
 *       through and the response is stored for the next replay.</li>
 *   <li><b>Failed responses (5xx/4xx) are NOT cached</b> — otherwise a transient
 *       outage would latch a failure for the entire 24 h TTL.</li>
 *   <li><b>Fail-open on Redis errors</b> — Redis hiccup must not break checkout.</li>
 * </ol>
 *
 * <p>Plus secondary checks: methods other than POST skip the filter, requests
 * without an {@code Idempotency-Key} header are passed through unchanged.
 */
@DisplayName("IdempotencyFilter — Stripe-pattern HTTP dedup contract")
class IdempotencyFilterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper mapper;
    private IdempotencyProperties props;
    private IdempotencyFilter filter;
    private FilterChain chain;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis    = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        mapper = new ObjectMapper();

        props = new IdempotencyProperties();
        props.setEnabled(true);
        props.setPaths(List.of("/api/orders"));
        props.setTtl(Duration.ofHours(24));

        filter = new IdempotencyFilter(redis, props, mapper);

        chain = mock(FilterChain.class);
    }

    // ── 1. Hit replays the cached response ────────────────────────────────

    @Test
    @DisplayName("Cache HIT (same body) → replays stored response, controller NOT invoked")
    void cacheHit_replaysCachedResponse() throws Exception {
        // Body and hash that the filter would compute.
        String body = "{\"ticketId\":\"t-1\"}";
        String hash = sha256Hex(body);

        // Pretend Redis already has a 201 response stored for this user+key+hash.
        var stored = new IdempotencyFilter.StoredResponse(
                201, "{\"data\":{\"id\":\"order-123\"}}", hash);
        when(valueOps.get("idem:user-A:key-1")).thenReturn(mapper.writeValueAsString(stored));

        var req  = postRequest("/api/orders", body, "user-A", "key-1");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        // The whole point: the controller (filter chain) must NOT run on a hit.
        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(201);
        assertThat(resp.getContentAsString()).isEqualTo("{\"data\":{\"id\":\"order-123\"}}");
    }

    // ── 2. Same key, different body → 422 ─────────────────────────────────

    @Test
    @DisplayName("Same key + DIFFERENT body → 422 IDEMPOTENCY_KEY_REUSED, controller NOT invoked")
    void sameKeyDifferentBody_rejectsWith422() throws Exception {
        // Stored entry has the hash of payload A.
        var stored = new IdempotencyFilter.StoredResponse(
                201, "{\"data\":{\"id\":\"order-1\"}}",
                sha256Hex("{\"ticketId\":\"t-1\"}"));
        when(valueOps.get("idem:user-A:key-1")).thenReturn(mapper.writeValueAsString(stored));

        // But this request is payload B with the same key.
        var req  = postRequest("/api/orders", "{\"ticketId\":\"t-DIFFERENT\"}", "user-A", "key-1");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(422);
        assertThat(resp.getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    // ── 3. Miss runs the chain + writes to Redis on 2xx ───────────────────

    @Test
    @DisplayName("Cache MISS + 2xx response → controller runs, response is cached for next time")
    void cacheMiss_runsChainAndStores() throws Exception {
        String body = "{\"ticketId\":\"t-1\"}";
        when(valueOps.get("idem:user-A:key-1")).thenReturn(null);

        // Simulate the controller writing a successful response. `doAnswer`
        // because FilterChain#doFilter is void — `when(...).then(...)` only
        // works on non-void methods.
        doAnswer(inv -> {
            jakarta.servlet.http.HttpServletResponse r = inv.getArgument(1);
            r.setStatus(201);
            r.setContentType("application/json");
            r.getWriter().write("{\"data\":{\"id\":\"order-new\"}}");
            return null;
        }).when(chain).doFilter(any(), any());

        var req  = postRequest("/api/orders", body, "user-A", "key-1");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
        // ContentCachingResponseWrapper buffers — the final copyBodyToResponse
        // happens inside the filter. So the real response sees status + body.
        assertThat(resp.getStatus()).isEqualTo(201);
        assertThat(resp.getContentAsString()).contains("order-new");

        // The stored entry must include the body hash so a later same-key
        // different-body request can be rejected.
        verify(valueOps).set(eq("idem:user-A:key-1"), any(String.class), any(Duration.class));
    }

    // ── 4. Non-2xx responses are NOT cached ────────────────────────────────

    @Test
    @DisplayName("4xx/5xx response → NOT cached (so future retries with same key go through)")
    void failureResponse_isNotCached() throws Exception {
        when(valueOps.get("idem:user-A:key-1")).thenReturn(null);
        doAnswer(inv -> {
            jakarta.servlet.http.HttpServletResponse r = inv.getArgument(1);
            r.setStatus(409);
            r.setContentType("application/json");
            r.getWriter().write("{\"errorCode\":\"TICKET_UNAVAILABLE\"}");
            return null;
        }).when(chain).doFilter(any(), any());

        var req  = postRequest("/api/orders", "{\"ticketId\":\"t-1\"}", "user-A", "key-1");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(409);
        // Critical: the failure response must NOT be cached. Otherwise the user
        // is permanently locked out of retrying with the same key for 24 h.
        verify(valueOps, never()).set(any(String.class), any(String.class), any(Duration.class));
    }

    // ── 5. Fail-open when Redis throws ────────────────────────────────────

    @Test
    @DisplayName("Redis lookup throws → controller still runs (fail open)")
    void redisDown_failsOpen() throws Exception {
        when(valueOps.get("idem:user-A:key-1"))
                .thenThrow(new RuntimeException("Redis is having a bad day"));
        doAnswer(inv -> {
            jakarta.servlet.http.HttpServletResponse r = inv.getArgument(1);
            r.setStatus(201);
            return null;
        }).when(chain).doFilter(any(), any());

        var req  = postRequest("/api/orders", "{\"x\":1}", "user-A", "key-1");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        // Failing checkout when Redis hiccups would be a much worse outcome
        // than allowing a small chance of duplicate within the outage window.
        verify(chain, atLeastOnce()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(201);
    }

    // ── Secondary: scope of the filter ────────────────────────────────────

    @Test
    @DisplayName("GET requests bypass the filter entirely — GET is already idempotent by spec")
    void getRequest_isSkipped() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/orders");
        req.addHeader("Idempotency-Key", "k");
        req.addHeader("X-User-Id", "u");

        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);   // pass-through with the ORIGINAL request
        verify(valueOps, never()).get(any(String.class));
    }

    @Test
    @DisplayName("POST to path not in idempotency.paths → bypass")
    void unconfiguredPath_isSkipped() throws Exception {
        var req = postRequest("/api/auth/login", "{}", "u", "k");
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(valueOps, never()).get(any(String.class));
    }

    @Test
    @DisplayName("Missing Idempotency-Key header → pass through, no Redis touch")
    void missingKey_passesThroughUntouched() throws Exception {
        // Configured path + POST but no header
        var req = new MockHttpServletRequest("POST", "/api/orders");
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-User-Id", "user-A");

        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
        verify(valueOps, never()).get(any(String.class));
    }

    @Test
    @DisplayName("Missing X-User-Id (no auth) → pass through — anonymous can't have idempotency scope")
    void missingUser_passesThroughUntouched() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/orders");
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("Idempotency-Key", "k");

        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
        verify(valueOps, never()).get(any(String.class));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static MockHttpServletRequest postRequest(String uri, String body, String userId, String key) {
        var req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (userId != null) req.addHeader("X-User-Id", userId);
        if (key != null)    req.addHeader("Idempotency-Key", key);
        return req;
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
