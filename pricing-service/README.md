# Pricing Service

**Port:** 8085

Manages dynamic ticket pricing with **point-in-time validation**. Prices fluctuate based
on demand and time-to-event. During a saga the service validates the user's submitted
price against the multiplier that was active when the order was created — preventing
both stale-price exploits and surprise surcharges. Streams live price updates to clients
via Server-Sent Events.

---

## Responsibilities

- Store and manage per-event price rules (face price, max surge, demand factor, time factor)
- Recalculate the active surge multiplier on a 30 s schedule
- **Validate the user's submitted price** against the multiplier history at order-creation time
- Detect price-change scenarios → ask the saga to pause for user confirmation
- Publish `price.updated` events for SSE-pushed live updates to connected browsers

---

## Internal architecture

```
PricingController
  └─ PricingService
        ├─ createRule / getRule / updateRule       [CRUD on EventPriceRule]
        ├─ lockPrice(cmd)                          [saga command handler]
        ├─ recalculatePrices()                     [@Scheduled fixedDelay=30s]
        └─ getEffectivePrice(ticketId)             [REST + SSE feed]

PricingCommandConsumer (Kafka)
  └─ pricing.lock.cmd → lockPrice()
```

### Dynamic surge calculation

```java
multiplier = 1.0 + (maxSurge - 1.0) × demandFactor × timeFactor
multiplier = clamp(multiplier, 1.0, maxSurge)

demandFactor = soldTickets / totalTickets
timeFactor   = 1.10 if hoursToEvent < 24 else 1.0
```

Recalculation publishes `PriceUpdatedEvent` and writes a new `price_history` row only when
the multiplier actually changes — preventing history-table bloat.

### Point-in-time price validation — three-case decision tree

When the saga sends `PriceLockCommand`, the service decides between three cases:

```
1. multiplierAtOrderTime = findMultiplierAt(eventId, orderCreatedAt)
   /* uses idx_price_history_event_time, partial unique idx_price_history_active */

2. expectedPrice = facePrice × multiplierAtOrderTime

3. ┌─ Case B: userPrice == expectedPrice
   │  → publish pricing.locked  (happy path, short-circuit before history check)
   │
   ├─ Case A: userPrice ≠ expected AND claimed multiplier never existed in recent window
   │  → publish pricing.failed (FABRICATED PRICE)
   │
   └─ Case C: userPrice ≠ expected BUT claimed multiplier existed in history
      → publish pricing.price.changed with newPrice
      → saga pauses in AWAITING_PRICE_CONFIRMATION
```

**Case B is checked first** so that a legitimate order whose surge has been stable longer
than the history-validity window is not rejected as fabricated.

### Confirmed re-lock (after user accepts a Case C price change)

When the saga re-issues `PriceLockCommand` with `confirmed=true`, validation is skipped
and the price is locked at the **current** multiplier × facePrice — exactly what the
user just agreed to.

---

## API endpoints

| Method | Path                                       | Auth        | Description                               |
| ------ | ------------------------------------------ | ----------- | ----------------------------------------- |
| `POST` | `/api/pricing/rules`                       | Yes (Admin) | Create price rule for event               |
| `GET`  | `/api/pricing/rules/{eventId}`             | Yes         | Get current rule (cached via Caffeine)    |
| `PUT`  | `/api/pricing/rules/{eventId}`             | Yes (Admin) | Update rule (maxSurge, totalTickets, …)   |
| `GET`  | `/api/pricing/tickets/{ticketId}/price`    | Yes         | Get effective price for a specific ticket |
| `GET`  | `/api/pricing/events/{eventId}/stream`     | Yes         | SSE — subscribe to live price updates     |

---

## Kafka topics

| Topic                   | Direction | Carries                                                |
| ----------------------- | --------- | ------------------------------------------------------ |
| `pricing.lock.cmd`      | Subscribe | `PriceLockCommand` from saga                           |
| `pricing.locked`        | Publish   | `PricingLockedEvent` — Case A or confirmed re-lock     |
| `pricing.price.changed` | Publish   | `PriceChangedEvent` — Case C, saga pauses for user     |
| `pricing.failed`        | Publish   | `PricingFailedEvent` — Case A, fabricated price        |
| `price.updated`         | Publish   | `PriceUpdatedEvent` — fan-out to SSE clients           |

---

## Normal flow — happy path (Case B)

```
saga → pricing.lock.cmd { orderId, ticketId, eventId, userPrice, facePrice, orderCreatedAt, confirmed=false }
  └─ multiplierAtOrderTime = findMultiplierAt(eventId, orderCreatedAt)
  └─ expectedPrice = facePrice × multiplierAtOrderTime
  └─ userPrice == expectedPrice  →  publish pricing.locked at expectedPrice
```

## Failure flows

| Scenario                              | Behaviour                                                   |
| ------------------------------------- | ----------------------------------------------------------- |
| No price rule for event               | Publish `pricing.failed` with reason `NO_PRICE_RULE`        |
| Claimed multiplier never existed      | Publish `pricing.failed` with reason `INVALID_PRICE` (Case A) |
| Price changed since order was created | Publish `pricing.price.changed` with new price (Case C)     |
| `orderCreatedAt` is null              | Failsafe — use current multiplier (logs warning)            |

---

## Persistence schema

**`event_price_rules`**:

```sql
id                UUID PK
event_id          VARCHAR(255) UNIQUE      -- one rule per event
event_name        VARCHAR(255)
face_price        NUMERIC(10,2)
max_surge         NUMERIC(6,4)             -- e.g. 2.0 = up to 2× face
surge_multiplier  NUMERIC(6,4)             -- current effective multiplier
demand_factor     DOUBLE PRECISION         -- soldTickets / totalTickets
total_tickets     INT
sold_tickets      INT
event_date        TIMESTAMPTZ
updated_at        TIMESTAMPTZ
```

**`price_history`** — append-only, with one active row per event:

```sql
id                UUID PK
event_id          VARCHAR(255)
surge_multiplier  NUMERIC(6,4)
valid_from        TIMESTAMPTZ
valid_to          TIMESTAMPTZ              -- NULL = currently active
triggered_by      VARCHAR(50)              -- DEMAND, MANUAL, SCHEDULED
created_at        TIMESTAMPTZ

-- Partial unique index — only ONE active row per event at any time
idx_price_history_active        ON (event_id) WHERE valid_to IS NULL
-- Range scan for point-in-time lookup (findMultiplierAt)
idx_price_history_event_time    ON (event_id, valid_from, valid_to)
```

---

## SSE flow

```
Client: GET /api/pricing/events/{eventId}/stream
  └─ SseEmitter registered for that eventId

recalculatePrices() (scheduled every 30s)
  └─ if multiplier changed → publish PriceUpdatedEvent on price.updated
  └─ push to all SseEmitters subscribed to that eventId
```

Nginx is configured with `proxy_buffering off` on `/api/*/stream` so events flush
immediately to the browser instead of being buffered.

---

## Dependencies

- **PostgreSQL** (`pricing_db`) — rules + price history + partial indexes
- **Redis** — caching layer for hot rule lookups (Caffeine L1 + Redis L2)
- **Kafka** — command in / events out
- **ticket-service** — HTTP call to fetch face price + eventId for a given ticketId (cached 10 min)
