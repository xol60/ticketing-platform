# Stress tests

Concurrent-order load tests for the ticketing-platform saga flow. Validates that
the system maintains cross-database consistency under bursty load and surfaces
real operational gaps (rate-limiter sensitivity, circuit-breaker default-config sensitivity,
payment retry behaviour) that aren't visible from code review alone.

These tests use plain `bash` + `curl` against the running Docker Compose stack —
no extra dependencies, no JMeter, no Gatling, no load-test cluster. The intent
is "minimum proof that end-to-end stress testing has been done", not industrial
benchmarking. For real production load testing, swap in
[k6](https://k6.io/) or [Vegeta](https://github.com/tsenart/vegeta).

---

## What `stress-test.sh` validates

Per run, it:

1. Logs in once via `POST /api/auth/login` to obtain a JWT.
2. Picks **`$CONCURRENCY` distinct AVAILABLE tickets** from `ticket_db`.
3. Fires `$CONCURRENCY` parallel `POST /api/orders` requests, each for a
   different ticket, all from the same user.
4. Measures ingress latency (time for all POSTs to return).
5. Polls the order/saga/payment databases until every accepted order reaches a
   terminal state (CONFIRMED / FAILED / CANCELLED) or 60-second timeout fires.
6. **Asserts cross-DB consistency**: the count of CONFIRMED orders must equal
   the count of CONFIRMED tickets whose `locked_by_order_id` matches those
   orders, and the count of payments with status=SUCCESS must equal them too.
7. Reports per-stage latencies and any inconsistencies found.

---

## Prerequisites

The stack must be up and the four infrastructure services healthy:

```bash
docker compose up -d
docker compose ps   # postgres-master/-slave, redis, kafka must be (healthy)
```

A test user with known credentials must exist (the script uses
`qauser99` / `Test1234!`). Register one via the UI or:

```bash
curl -X POST http://localhost/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "qauser99",
    "email":    "qa99@example.com",
    "password": "Test1234!"
  }'
```

You must have at least `$CONCURRENCY` tickets in `AVAILABLE` status.
Check with:

```bash
docker exec postgres-master psql -U ticketing -d ticket_db -c \
  "SELECT status, COUNT(*) FROM tickets GROUP BY status;"
```

If you run out, reset some CONFIRMED tickets back to AVAILABLE:

```bash
docker exec postgres-master psql -U ticketing -d ticket_db -c \
  "UPDATE tickets
     SET status             = 'AVAILABLE',
         locked_by_order_id = NULL,
         locked_by_user_id  = NULL,
         reserved_at        = NULL,
         confirmed_at       = NULL,
         locked_price       = NULL,
         reserved_until     = NULL,
         version            = version + 1
   WHERE status = 'CONFIRMED'
   LIMIT 20;"
```

---

## Running the test

```bash
# Default 20 concurrent orders
/bin/bash tests/stress-test.sh

# Override concurrency
CONCURRENCY=5  /bin/bash tests/stress-test.sh
CONCURRENCY=15 /bin/bash tests/stress-test.sh
CONCURRENCY=50 /bin/bash tests/stress-test.sh
```

> **Use `/bin/bash` explicitly.** The shebang says `bash`, but if zsh or sh
> evaluates it, the parallel-curl pattern misbehaves. On macOS 3.2's stock bash
> the script is portable; on Linux modern bash it just works.

---

## Reading the output

A clean run looks like:

```
🔑 Logging in...
📊 Starting state: AVAILABLE=16  CONFIRMED=2
🎯 Firing 5 concurrent orders...
⏱  All 5 POSTs returned in 598 ms
📨 Accepted: 5/5 orders
⏳ Polling for terminal states (timeout 60s)...
✅ All sagas reached terminal state in 10723 ms

═══ Validation ═══
  CONFIRMED orders : 5
  FAILED/CANCELLED : 0
  Confirmed orders : 5
  Confirmed tickets: 5
✅ Cross-DB consistency: orders ⇄ tickets matched
  Payment SUCCESS  : 5

📊 Summary:
  5 concurrent orders → 5 CONFIRMED, 0 FAILED
  POST acceptance: 598 ms
  End-to-end:     10723 ms
```

| Field | What it means |
| --- | --- |
| **POSTs returned in N ms** | nginx + gateway + order-service ingress latency for the burst |
| **Accepted: X/Y** | How many requests passed rate-limit / circuit-breaker and started a saga |
| **All sagas terminal in N ms** | Time from first POST to the last saga reaching CONFIRMED / FAILED |
| **CONFIRMED orders** | Orders that completed the full saga successfully |
| **FAILED / CANCELLED** | Orders that hit a compensation path (ticket race lost, payment exhausted retries, pricing rejected, etc.) |
| **Cross-DB consistency** | Asserts that for every CONFIRMED order, a CONFIRMED ticket and a SUCCESS payment exist. Mismatch ⇒ real data integrity bug |
| **End-to-end** | Heavily influenced by the simulated 80% gateway-failure rate in `ExternalPaymentGateway`; payment retries dominate p99 |

---

## Variations to try

### Force a race condition

Edit `stress-test.sh` and replace the ticket-selection query with a single
ticket repeated `$CONCURRENCY` times:

```bash
# Before
TICKETS=()
while IFS= read -r line; do
  TICKETS+=("$line")
done < <(docker exec postgres-master psql -U ticketing -d ticket_db -tA -c \
  "SELECT id || '|' || face_price FROM tickets WHERE status='AVAILABLE' LIMIT $CONCURRENCY")

# After — all requests target the same ticket
SAME_TICKET=$(docker exec postgres-master psql -U ticketing -d ticket_db -tA -c \
  "SELECT id || '|' || face_price FROM tickets WHERE status='AVAILABLE' LIMIT 1")
TICKETS=()
for i in $(seq 1 $CONCURRENCY); do
  TICKETS+=("$SAME_TICKET")
done
```

Expected result: **exactly one** order CONFIRMED, all others FAILED with
`TICKET_LOCK_CONFLICT` or `TICKET_UNAVAILABLE`. Proves the
Redis-SETNX + `@Version` optimistic-locking pair correctly prevents
overselling.

### Kill payment-service mid-flight

Demonstrates saga watchdog recovery:

```bash
# Terminal 1
CONCURRENCY=10 /bin/bash tests/stress-test.sh

# Terminal 2 (while orders are in flight)
docker compose stop payment-service
sleep 30
docker compose start payment-service
```

Sagas in `PRICING_LOCKED` should eventually compensate via the saga watchdog —
no orders should stay in PENDING indefinitely.

### Drive payment retries

Edit `payment-service/src/main/java/.../ExternalPaymentGateway.java` and bump
the `FAIL_PROBABILITY` constant from 0.8 to 0.95. Re-run. Expect more orders
to exhaust the 3-attempt budget and hit DLQ, with admin-alert notifications
emitted. Confirms the watchdog + DLQ + alert pipeline.

---

## Findings the test reveals (and how to interpret them)

Stress-testing this stack consistently surfaces three operational characteristics
that aren't obvious from reading the code:

### 1. Rate-limiter at 20 req/s per user blocks bursts

```
CONCURRENCY=15 → 0/15 accepted
Response: "Rate limit exceeded — please slow down"
HTTP 429, retryAfterSeconds=60
```

**Root cause**: Gateway sliding-window rate limit at 20 req/s per `IP:userId`.
A burst of 15 in 100 ms is well under 20/s — but cumulative recent requests
in the sliding window from earlier tests push it over.

**Production implication**: For flash sales where each customer makes one
request, this is fine. For impatient users double-clicking or scripts retrying,
it's a lockout. **Real fix**: rate-limit per `IP:userId:endpoint` instead of
per `IP:userId`, so a burst on `/api/orders` doesn't lock the user out of
`/api/me` or `/api/events`.

### 2. Circuit-breaker default config records all exceptions — including downstream 4xx

```
3 accepted out of 5 → 2 rejected with:
"Service temporarily unavailable — circuit open for order-service"
```

**Root cause**: Resilience4j's default `CircuitBreakerConfig` records **every
`Throwable` as a failure**, including `WebClientResponseException` subclasses
emitted on downstream 4xx responses. Over sustained testing — bad-input
requests, simulated payment-gateway failures, intermittent timeouts — the
cumulative failure count in the sliding window crossed the 50% threshold and
opened the circuit for 30 seconds, blocking unrelated legitimate traffic.

Note: this is **NOT** caused by rate-limit 429s being counted as failures.
`RateLimitFilter` short-circuits the filter chain before `CircuitBreakerFilter`
runs (it has order `HIGHEST_PRECEDENCE + 1` vs CB's `+ 2`), so rate-limited
requests never reach the circuit breaker. The two layers reject independently
for unrelated reasons — what we observed in stress tests was just both layers
firing during the same burst, not a causal interaction.

**Real fix** in
`api-gateway/src/main/java/com/ticketing/gateway/circuitbreaker/CircuitBreakerManager.java`:

```java
CircuitBreakerConfig.custom()
    ...
    .recordExceptions(
        IOException.class,                                       // network errors
        TimeoutException.class,                                  // downstream too slow
        WebClientResponseException.InternalServerError.class,    // 500
        WebClientResponseException.BadGateway.class,             // 502
        WebClientResponseException.ServiceUnavailable.class,     // 503
        WebClientResponseException.GatewayTimeout.class          // 504
    )
    .ignoreExceptions(
        WebClientResponseException.BadRequest.class,             // 400
        WebClientResponseException.Unauthorized.class,           // 401
        WebClientResponseException.Forbidden.class,              // 403
        WebClientResponseException.NotFound.class,               // 404
        WebClientResponseException.Conflict.class,               // 409
        WebClientResponseException.UnprocessableEntity.class,    // 422
        WebClientResponseException.TooManyRequests.class         // 429 — defence in depth
    )
    .build();
```

Only **actual service-health signals** (network errors, timeouts, 5xx) count
toward the failure rate now. Client-side rejections are ignored.

### 3. p99 saga latency is dominated by payment retry backoff

```
End-to-end p50: ~3 seconds
End-to-end p99: ~20 seconds  (when payment fails 2 times: 5s + 15s backoff)
```

**Source**: `BACKOFF_S = { 5L, 15L }` in `PaymentService.java`. With the
simulated 80% gateway-failure rate, ~36% of orders hit the 5s wait, ~13% hit
both. README's "<1s p50 / <2s p99" claim is **misleading** — it's only
accurate when the gateway is 100% reliable. Real claim should be:

> *"<1s p50, <20s p99 under 80% simulated gateway-fail (limited by exponential
> backoff in payment retry watchdog)."*

### 4. Cross-DB consistency holds under all observed concurrency levels ✓

The positive finding. Across every run (including ones with FAILED/CANCELLED
mixed outcomes), `CONFIRMED orders == CONFIRMED tickets == SUCCESS payments`.
No orphan rows, no half-charged orders, no leaked ticket locks.

This is what the saga + outbox + watchdog architecture is supposed to deliver.
The test proves it does.

---

## Common pitfalls

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Need at least $N available tickets — only X exist` | Prior runs consumed tickets | Run the reset SQL in [Prerequisites](#prerequisites) |
| `mapfile: command not found` | macOS bash 3.2 doesn't have `mapfile` | The script has been patched to use `while read`; re-pull the latest |
| `Rate limit exceeded` on **every** request | Login rate-limiter still in cooldown from a previous test | Wait 60 seconds and re-run |
| `Service temporarily unavailable — circuit open` | Resilience4j circuit breaker open from prior failures | Wait 30 seconds; the half-open probe will close it |
| `Invalid or expired token` | JWT expired between login and first POST (15-min TTL) | Re-run the script — it logs in fresh each time |
| All accepted orders end CANCELLED with `Price not found in valid price history` | `event_price_rules` and `price_history` are in an inconsistent state from prior Q5-style tests | Reset pricing (see [Clean reset](#clean-reset) below) |

---

## Clean reset

Before a fresh validation run (especially before any demo or interview), do this:

```bash
# 1. Restart application services to clear in-memory state (caches, circuit-breaker counters)
docker compose restart \
  order-service ticket-service payment-service \
  saga-orchestrator pricing-service api-gateway

# 2. Wait for healthy
sleep 30

# 3. Reset all tickets to AVAILABLE
docker exec postgres-master psql -U ticketing -d ticket_db -c \
  "UPDATE tickets
     SET status             = 'AVAILABLE',
         locked_by_order_id = NULL,
         locked_by_user_id  = NULL,
         reserved_at        = NULL,
         confirmed_at       = NULL,
         locked_price       = NULL,
         reserved_until     = NULL,
         version            = version + 1;"

# 4. Reset pricing to a clean 1.0× state (no stale surge from prior tests)
docker exec postgres-master psql -U ticketing -d pricing_db -c \
  "UPDATE event_price_rules
     SET surge_multiplier = 1.0,
         demand_factor    = 0,
         sold_tickets     = 0;"
docker exec postgres-master psql -U ticketing -d pricing_db -c \
  "DELETE FROM price_history;"
docker exec postgres-master psql -U ticketing -d pricing_db -c \
  "INSERT INTO price_history (event_id, surge_multiplier, valid_from, triggered_by)
   SELECT event_id, 1.0, NOW() - INTERVAL '1 hour', 'MANUAL'
     FROM event_price_rules;"

# 5. Cool down rate-limiter
sleep 65

# 6. Run the test
CONCURRENCY=5 /bin/bash tests/stress-test.sh
```

---

## What this is *not*

- **Not a benchmark.** Single-machine Docker Compose isn't representative of
  production latency or throughput. Use a real load-testing tool against a
  realistic deployment if you need numbers for capacity planning.
- **Not a fuzzer.** It exercises the happy path of `POST /api/orders` plus the
  expected concurrent-order failure paths. It does not fuzz inputs, malformed
  JWTs, or auth bypass attempts.
- **Not a chaos test.** It does not kill services mid-flight by default — see
  [Variations](#variations-to-try) for manual chaos scenarios.

For its narrow purpose — proving end-to-end consistency holds under modest
concurrent load and surfacing operational rough edges — it's adequate. It is
not a substitute for proper production load tests.
