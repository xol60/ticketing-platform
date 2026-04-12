# Order Service

**Port:** 8083

Creates and tracks purchase orders. An order is a user's intent to buy a ticket. Once created, the order hands off to the saga-orchestrator via Kafka and waits for a terminal outcome (CONFIRMED or FAILED).

---

## Responsibilities

- Accept and persist `CreateOrderRequest` from authenticated users
- Validate that the ticket's event is open for sales (HTTP call to ticket-service, fail-open)
- Publish `order.created` event to trigger the saga
- Listen for saga outcomes (`order.confirmed`, `order.failed`) and update order status
- Serve order status reads with L1 + L2 caching

---

## Internal Architecture

```
OrderController
  → OrderService
      ├── createOrder()
      │     → EventValidationClient.isEventOpenForSales(ticketId)  [HTTP, fail-open]
      │     → persist PENDING order
      │     → publish order.created
      │
      ├── getOrder()        [L1 Caffeine → L2 Redis → DB (slave)]
      ├── getOrdersByUser()
      ├── handleConfirmed() [Kafka consumer, marks CONFIRMED]
      └── handleFailed()    [Kafka consumer, marks FAILED]
```

### Two-layer caching

| Layer | Technology | TTL |
|-------|-----------|-----|
| L1 | Caffeine | 5 min |
| L2 | Redis (`order:{id}`) | 5 min |

Order status changes (confirmed / failed) evict both layers.

### Event validation

Before persisting a new order, `EventValidationClient` makes a synchronous HTTP `GET /internal/tickets/{ticketId}/event-status` call to ticket-service to check if the event is open for sales.

- If ticket-service says **closed** → reject immediately with `400 Bad Request`
- If ticket-service is **unreachable** → allow the order (fail-open); the saga guard in ticket-service is the authoritative check and will reject at the reservation step if needed

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/orders` | Yes | Create order for a ticket |
| `GET` | `/api/orders/{id}` | Yes | Get order by ID |
| `GET` | `/api/orders/me` | Yes | All orders for the authenticated user |

### Request body — create order

```json
{
  "ticketId": "uuid",
  "requestedPrice": 99.00
}
```

### Response

```json
{
  "orderId": "uuid",
  "userId": "uuid",
  "ticketId": "uuid",
  "status": "PENDING",
  "requestedPrice": 99.00,
  "finalPrice": null,
  "paymentReference": null,
  "createdAt": "..."
}
```

---

## Order Status Lifecycle

```
PENDING → CONFIRMED  (saga success)
        → FAILED     (saga failure at any step)
```

---

## Normal Flow

```
POST /api/orders { ticketId, requestedPrice }
  → AuthFilter sets X-User-Id header
  → EventValidationClient checks event is open (HTTP, ticket-service)
  → Order saved as PENDING
  → order.created published to Kafka
  → saga-orchestrator drives reserve → price lock → payment → confirm
  → order.confirmed consumed → status = CONFIRMED, finalPrice set
  → Response: 201 Created { orderId, status: PENDING }
```

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| Event closed (ticket-service reachable) | `400 Bad Request` before order is created |
| ticket-service unreachable | Order created; saga guard rejects at reserve step; order → FAILED |
| Ticket unavailable | Saga fails; `order.failed` consumed; status = FAILED |
| Payment declined | Saga compensates ticket; `order.failed` consumed; status = FAILED |
| Saga watchdog timeout | Saga compensates; `order.failed` consumed; status = FAILED |

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `order.created` | Publish | Triggers saga |
| `order.confirmed` | Subscribe | Saga success — mark CONFIRMED |
| `order.failed` | Subscribe | Saga failure — mark FAILED with reason |

---

## Dependencies

- **PostgreSQL** (`order_db`) — order persistence
- **Redis** — L2 cache
- **Kafka** — publish `order.created`; consume `order.confirmed`, `order.failed`
- **ticket-service** — HTTP (fail-open) event validation before creating order
