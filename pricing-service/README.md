# Pricing Service

**Port:** 8085

Manages dynamic ticket pricing. Prices fluctuate based on demand, time-to-event, and configurable rules. During a saga, the service locks a price to a specific order so the buyer pays the price they saw when they started the purchase. Pushes real-time price updates to clients via Server-Sent Events (SSE).

---

## Responsibilities

- Store and manage per-event price rules (base price, multipliers, flash-sale thresholds)
- Recalculate prices on a schedule based on demand signals
- Lock a price for a saga (so the charged amount is deterministic)
- Unlock a locked price on saga compensation
- Stream live price updates to connected clients via SSE
- Publish `price.updated` events to Kafka when prices change

---

## Internal Architecture

```
PricingController
  → PricingService
      ├── createRule / getRule / updateRule  [CRUD on PriceRule]
      ├── lockPrice(cmd)     [saga command handler]
      ├── unlockPrice(cmd)   [saga compensation]
      ├── recalculatePrices() [scheduled]
      └── registerSseEmitter(eventId) [SSE subscription]

PricingCommandConsumer (Kafka)
  → pricing.lock.cmd   → lockPrice()
  → pricing.unlock.cmd → unlockPrice()
```

### Dynamic pricing

`recalculatePrices()` runs on a configurable schedule. For each active event it:
1. Reads current demand (reservation count, time remaining)
2. Applies the configured multiplier rules
3. Saves the new price to the DB
4. Publishes `price.updated` to Kafka
5. Pushes the update to all active SSE subscribers for that event

### Price locking

When the saga requests a price lock:
1. The current effective price is read
2. A `PriceLock` record is written: `{ lockId, orderId, sagaId, ticketId, lockedPrice, expiresAt }`
3. The locked price is returned in `pricing.locked` so downstream (payment) knows the exact amount

On compensation (`pricing.unlock.cmd`), the lock is removed. The ticket price returns to the market rate.

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/pricing/rules` | Yes (Admin) | Create price rule for event |
| `GET` | `/api/pricing/rules/{eventId}` | Yes | Get current price rule |
| `PUT` | `/api/pricing/rules/{eventId}` | Yes (Admin) | Update price rule |
| `GET` | `/api/pricing/events/{eventId}/stream` | Yes | SSE — subscribe to price updates |

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `pricing.lock.cmd` | Subscribe | Lock price for a saga |
| `pricing.locked` | Publish | Confirms locked price to saga-orchestrator |
| `pricing.unlock.cmd` | Subscribe | Release a price lock (compensation) |
| `price.updated` | Publish | Broadcast new price to Kafka (and SSE push) |

---

## Normal Flow — Price Lock

```
saga sends pricing.lock.cmd { sagaId, orderId, ticketId }
  → PricingService.lockPrice()
  → read effective price for ticket's event
  → write PriceLock record
  → publish pricing.locked { sagaId, orderId, lockedPrice }
```

## Normal Flow — SSE Price Push

```
Client: GET /api/pricing/events/{eventId}/stream
  → SseEmitter registered for that eventId

recalculatePrices() (scheduled)
  → compute new price
  → save to DB
  → publish price.updated to Kafka
  → push to all registered SseEmitters for eventId
```

---

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| No price rule found | `pricing.locked` with current base price (fail-open) |
| Lock record conflict (same orderId) | Idempotent — return existing lock |
| Compensation (unlock) for unknown lock | Log and ignore |
| SSE client disconnects | Emitter is removed from registry |

---

## Dependencies

- **PostgreSQL** (`pricing_db`) — price rules and lock records
- **Redis** — (optional) caching of current effective prices
- **Kafka** — command/event topics for saga integration
