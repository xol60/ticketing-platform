# Request Idempotency — Defence in Depth

Mutating POST endpoints (`POST /api/orders`, `POST /api/secondary/listings`)
are protected by a three-tier stack. Each tier catches a different failure
mode; if one is bypassed, the next still catches the duplicate.

The user-facing outcome of all this work is: **three rapid POSTs of the same
intent produce exactly one row in the database**.

---

## Tier 1 — UI: keep the duplicate intent in the browser

Lives in [`ticketing-ui`](../ticketing-ui).

- **Button disabled while the mutation is pending.** React Query's
  `useMutation` flips the Buy / List button to disabled the instant the user
  clicks, re-enables on settle. Catches the same-tab same-second
  double-click.
- **Buy-button gating on existing orders.** `EventDetailPage` indexes the
  user's orders by `ticketId`; any non-terminal order (`PENDING` /
  `PRICE_CHANGED` / `CONFIRMED`) renders "⏳ In progress" or "✓ Owned"
  instead of "Buy". Catches cross-tab clicks, return-to-page-after-leave,
  refresh-then-retry.
- **Client-generated `Idempotency-Key` (UUIDv4) per click.** Same key reused
  across React Query's auto-retries. Alone it catches nothing — it's the
  pairing with Tier 2 that makes network retries safe.

---

## Tier 2 — HTTP boundary: Stripe-pattern dedup

Lives in [`common-lib`](../common-lib) as the `IdempotencyFilter`. Opt-in per
service via `idempotency.enabled: true` + `idempotency.paths: [...]`.

Currently enabled in:
- [`order-service`](../order-service) on `POST /api/orders`
- [`secondary-market-service`](../secondary-market-service) on `POST /api/secondary/listings`

```
on POST {configured path}:
  read Idempotency-Key header
  read X-User-Id (injected by gateway)
  hash request body with SHA-256
  redis: GET idem:{userId}:{key}
    miss               → run controller, cache 2xx response for 24 h
    hit, same hash     → REPLAY cached response (controller never runs)
    hit, different hash → 422 IDEMPOTENCY_KEY_REUSED
  redis throws         → log + pass through (fail-open)
```

- Failures (non-2xx) are deliberately **not** cached — a transient outage
  shouldn't latch a failure response for 24 h.
- Per-user scope on the dedup key makes cross-user collision attacks
  impossible — a malicious key can only collide with the attacker's own.
- 9 unit tests in `common-lib` cover hit, miss, 422, failure-not-cached,
  fail-open, GET-bypass, unconfigured-path bypass, missing-key, missing-user.

---

## Tier 3 — Async / saga / DB: the safety net

Even if a duplicate slips past Tier 1 and 2 (e.g. DevTools-crafted retry with
a fresh UUID), the existing saga safety nets still catch it:

- **`orderId`-keyed Kafka partitioning** keeps per-order commands sequential
  ([`saga-orchestrator`](../saga-orchestrator)).
- **Redis `SETNX` on `ticket:lock:{ticketId}`** sub-millisecond-fails a
  second saga trying to reserve the same ticket ([`ticket-service`](../ticket-service)).
- **Status-and-owner idempotency** in `handleReserveCommand` /
  `handleConfirmCommand` handles Kafka redelivery: if the ticket is already
  in the expected state owned by this order, the handler republishes the
  prior success event instead of re-processing.
- **JPA optimistic locking (`@Version`)** catches any race that bypassed
  Redis (Redis outage, lock TTL expired mid-flight, direct SQL).
- **Hybrid transactional outbox** ensures a Kafka publish failure during a
  write tx doesn't lose the event — it's persisted in DB and retried.
- **Stuck-reservation watchdog** (every 60 s) releases `RESERVED` tickets
  past their `reservedUntil` deadline so a saga that crashed mid-flow
  doesn't lock the ticket forever.

---

## Verified outcome

Sending three POSTs of the same intent — two with the same `Idempotency-Key`
and one with a different body — yields:

| POST | Status | Result |
|------|--------|--------|
| #1, key X, body A | 201 | New order O created |
| #2, key X, body A | 201 | **Replay** — same orderId, same traceId, controller never runs |
| #3, key X, body B | 422 | `IDEMPOTENCY_KEY_REUSED` |

DB count for `(userId, ticketId)`: **exactly one row**.

---

## What we deliberately did NOT build

- **Intent-window dedup** (same userId + same ticketId + same amount within
  30 s blocks the second attempt regardless of key). Not built because every
  ticket has a unique row and the SETNX in Tier 3 already catches the
  duplicate. The day a bulk-buy or wallet top-up endpoint ships, this
  becomes mandatory.
- **HMAC-signed idempotency keys.** Per-user scoping makes cross-user
  collision attacks impossible, so signing buys nothing for the current
  trust model. Would be useful for a future partner API.
- **`localStorage`-persisted UUIDs.** Means a force-quit between POST send
  and response receipt produces a fresh UUID on next launch — server sees
  the resumed attempt as a new intent. Acceptable for the current SPA;
  mandatory for a mobile app.
