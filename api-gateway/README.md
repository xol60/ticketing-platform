# API Gateway

**Port:** 8090 (Nginx sits in front on port 80)

Spring Cloud Gateway — the single entry point for all client traffic. Handles JWT authentication, request tracing, rate limiting, and circuit breaking before forwarding requests to downstream services.

---

## Responsibilities

- **Routing** — path-based routing to each microservice
- **JWT Authentication** — validates tokens once; sets `X-User-Id`, `X-User-Role` headers for downstream services
- **Request tracing** — generates `X-Trace-Id` and propagates it on every request
- **Rate limiting** — sliding window counter per `userId:IP` stored in Redis (configurable requests/second)
- **Circuit breaking** — Resilience4j circuit breaker per upstream service

---

## Internal Architecture

```
Incoming request
  → TraceIdFilter          (inject/propagate X-Trace-Id)
  → SlidingWindowRateLimiter (Redis key: "rate:{userId}:{ip}")
  → AuthFilter             (skip public paths; validate JWT from header/Redis)
  → Spring Cloud Gateway routing
  → downstream service
```

### Authentication flow

1. `AuthFilter` checks whether the path is in the public whitelist (e.g. `/api/auth/**`).
2. For protected paths, the JWT is extracted from the `Authorization: Bearer <token>` header.
3. The signature is verified (HMAC-SHA256) using the shared `JWT_SECRET`.
4. The filter checks a Redis revocation set — if the token's `jti` is revoked, the request is rejected with `401`.
5. On success, `X-User-Id` and `X-User-Role` are forwarded downstream. The raw JWT is stripped.

### Rate limiting

Redis sliding window per user (`rate:{userId}:{ip}`). Requests exceeding the window return `429 Too Many Requests`. Window size and limit are configurable via `application.yml`.

---

## Routes

| Path prefix | Downstream service | Auth required |
|-------------|-------------------|---------------|
| `/api/auth/**` | auth-service:8081 | No |
| `/api/tickets/**` | ticket-service:8082 | Yes |
| `/api/orders/**` | order-service:8083 | Yes |
| `/api/pricing/**` | pricing-service:8085 | Yes |
| `/api/reservations/**` | reservation-service:8086 | Yes |
| `/api/payments/**` | payment-service:8087 | Yes |
| `/api/market/**` | secondary-market-service:8088 | Yes |

Internal endpoints (`/internal/**`) are never exposed by the gateway.

---

## Normal Flow

1. Client sends `POST /api/orders` with `Authorization: Bearer <jwt>`
2. TraceIdFilter injects `X-Trace-Id` (UUID)
3. RateLimitFilter checks the sliding window — allow or `429`
4. AuthFilter validates JWT; injects `X-User-Id: <userId>` header
5. Gateway forwards to `order-service:8083/api/orders`
6. Response returned to client

---

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| Invalid / expired JWT | `401 Unauthorized` |
| Revoked token (in Redis) | `401 Unauthorized` |
| Rate limit exceeded | `429 Too Many Requests` |
| Downstream service down | Circuit breaker opens → `503 Service Unavailable` |
| Downstream timeout | `504 Gateway Timeout` |

---

## Configuration

```yaml
gateway:
  jwt-secret: ${JWT_SECRET}
  rate-limit:
    capacity: 20        # max burst
    refill-per-second: 5
  public-paths:
    - /api/auth/**
```

---

## Dependencies

- **Redis** — rate limit counters, token revocation set
- **All downstream services** — routes requests to each
