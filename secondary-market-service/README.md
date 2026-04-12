# Secondary Market Service

**Port:** 8088

Peer-to-peer ticket resale marketplace. Ticket holders can list their confirmed tickets for resale at any price. Buyers purchase listings through the same saga flow as primary-market orders, ensuring the same concurrency safety and payment guarantees.

---

## Responsibilities

- Allow sellers to list their confirmed tickets for resale
- Allow buyers to browse active listings by event
- Process purchases via the saga orchestrator (same flow as primary market)
- Validate that the event is still open for sales before accepting a purchase
- Prevent double-selling with pessimistic DB locks on purchase

---

## Internal Architecture

```
SecondaryMarketController
  → SecondaryMarketService
      ├── createListing(request, sellerId)
      ├── getListingsByEvent(eventId)     [L1 Caffeine → L2 Redis → DB (slave)]
      ├── getListing(id)
      ├── cancelListing(id, userId)
      └── purchaseListing(id, buyerId, traceId)
            → EventValidationClient.isEventOpenForSales(eventId)  [HTTP, fail-open]
            → SELECT FOR UPDATE listing
            → validate ACTIVE + not self-purchase
            → mark SOLD, generate orderId + sagaId
            → publish order.created (same topic as order-service)
            → saga takes over from here
```

### Two-layer caching

| Layer | Technology | TTL |
|-------|-----------|-----|
| L1 | Caffeine (per-instance) | configurable |
| L2 | Redis (`listings:event:{eventId}`) | explicit eviction on writes |

### Event validation

Before processing a purchase, `EventValidationClient` calls `GET /internal/events/{eventId}/status` on ticket-service:
- **Event closed** → reject with `400 Bad Request`
- **Unreachable** → allow (fail-open); saga guard is authoritative

### Concurrency safety

`purchaseListing()` uses `SELECT ... FOR UPDATE` (pessimistic lock) on the listing row. Only one concurrent buyer can reach the `setStatus(SOLD)` line.

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/market/listings` | Yes | Create resale listing |
| `GET` | `/api/market/listings?eventId=` | Yes | Browse active listings for event |
| `GET` | `/api/market/listings/{id}` | Yes | Get listing by ID |
| `DELETE` | `/api/market/listings/{id}` | Yes | Cancel own listing |
| `POST` | `/api/market/listings/{id}/purchase` | Yes | Buy a listing |

### Create listing request

```json
{
  "ticketId": "uuid",
  "eventId":  "uuid",
  "askPrice": 150.00
}
```

---

## Listing Status Lifecycle

```
ACTIVE → SOLD      (buyer purchases)
       → CANCELLED (seller cancels)
```

---

## Normal Flow — Purchase

```
POST /api/market/listings/{id}/purchase
  → EventValidationClient checks event open (HTTP, ticket-service, fail-open)
  → SELECT FOR UPDATE listing
  → validate: ACTIVE, seller != buyer
  → set status = SOLD, purchasedByUserId, purchasedOrderId
  → publish order.created { sagaId, orderId, buyerId, ticketId, askPrice }
  → same saga as primary-market: reserve → price lock → payment → confirm
```

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| Event closed (ticket-service reachable) | `400 Bad Request` immediately |
| ticket-service unreachable | Order created; saga guard rejects at ticket reserve step |
| Listing already SOLD | Listing lock prevents this; `409` if race condition is caught |
| Seller tries to buy own listing | `400 Bad Request` |
| Listing is CANCELLED | `409 Conflict` |
| Payment fails | Saga compensates ticket; listing may need manual re-activation (admin) |

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `order.created` | Publish | Triggers saga (same topic as order-service) |

---

## Dependencies

- **PostgreSQL** (`secondary_market_db`) — listing records
- **Redis** — L2 cache, cache eviction on state changes
- **Kafka** — publish `order.created`
- **ticket-service** — HTTP event validation before purchase (fail-open)
