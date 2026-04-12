# Ticket Service

**Port:** 8082

The aggregate root for the ticketing domain. Owns two bounded contexts: **Events** (scheduling and lifecycle) and **Tickets** (inventory, seat assignments, status). All ticket-state transitions during a purchase flow are driven by saga commands from the saga-orchestrator.

---

## Responsibilities

- Event CRUD and lifecycle management (DRAFT → OPEN → SALES_CLOSED → COMPLETED / CANCELLED)
- Ticket inventory management (create, update, delete seats)
- Executing saga reservation commands (reserve, confirm, release)
- Publishing state-change events to Kafka for downstream consumers
- Providing internal HTTP endpoints for event status validation (used by order-service and secondary-market-service)

---

## Internal Architecture

```
TicketController        EventController
      │                      │
TicketService           EventService
      │                      │
TicketRepository     EventRepository   ← both backed by ticket_db (PostgreSQL)
      │
TicketCommandConsumer (Kafka)
      │
  handleReserveCommand()
  handleConfirmCommand()
  handleReleaseCommand()
  handleCompensation()
      │
TicketEventPublisher (Kafka) → publishes reserved/confirmed/released events
```

### Two-layer caching

| Layer | Technology | Scope | TTL |
|-------|-----------|-------|-----|
| L1 | Caffeine (in-process) | per-instance | 30 s |
| L2 | Redis | cross-instance | 30 s |

Read path: L1 → L2 → PostgreSQL (slave). Every write evicts both L1 and L2.

### Concurrency protection

Three layers to prevent double-booking:

1. **Redis NX lock** (`ticket:lock:{ticketId}`) — acquired before any reserve command is processed; released in `finally`. Prevents concurrent sagas targeting the same ticket.
2. **Pessimistic DB lock** — `SELECT ... FOR UPDATE` on the ticket row during reserve/confirm/release.
3. **Optimistic lock** — `@Version` on the Ticket entity catches any remaining races at flush time.

---

## API Endpoints

### Ticket endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/tickets` | Yes (Admin) | Create a ticket |
| `GET` | `/api/tickets/{id}` | Yes | Get ticket by ID |
| `GET` | `/api/tickets?eventId=` | Yes | All tickets for an event |
| `GET` | `/api/tickets/available?eventId=` | Yes | Available tickets for event |
| `PUT` | `/api/tickets/{id}` | Yes (Admin) | Update ticket |
| `DELETE` | `/api/tickets/{id}` | Yes (Admin) | Delete ticket (AVAILABLE only) |

### Event admin endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/tickets/events` | Yes (Admin) | Create event |
| `PATCH` | `/api/tickets/events/{id}/open` | Yes (Admin) | Open event for sales |
| `PATCH` | `/api/tickets/events/{id}/cancel` | Yes (Admin) | Cancel event |
| `PATCH` | `/api/tickets/events/{id}/close` | Yes (Admin) | Close sales |
| `GET` | `/api/tickets/events` | Yes | List open events |

### Internal endpoints (not exposed by gateway)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/internal/events/{eventId}/status` | Event status by eventId (used by secondary-market-service) |
| `GET` | `/internal/tickets/{ticketId}/event-status` | Event status via ticketId (used by order-service) |

---

## Saga Command Handling

Ticket-service listens on these Kafka topics:

| Topic | Handler | Action |
|-------|---------|--------|
| `ticket.reserve.cmd` | `handleReserveCommand` | Acquires Redis lock → checks availability → validates event open → calls `ticket.reserve()` → publishes `ticket.reserved` or `ticket.released` |
| `ticket.confirm.cmd` | `handleConfirmCommand` | Validates orderId matches → calls `ticket.confirm()` → publishes `ticket.confirmed` |
| `ticket.release.cmd` | `handleReleaseCommand` | Validates orderId matches → calls `ticket.release()` → publishes `ticket.released` |
| `saga.compensate` | `handleCompensation` | Same as release — rolls back reservation |

---

## Event Lifecycle

```
DRAFT → OPEN → SALES_CLOSED
            ↘ CANCELLED
OPEN → COMPLETED (after event date)
```

`isOpenForSales()` returns `true` only when:
- status == OPEN
- now > salesOpenAt
- now < salesCloseAt
- now < eventDate

---

## Normal Flow — Reserve

```
saga-orchestrator sends ticket.reserve.cmd
  → acquire Redis NX lock (30s TTL)
  → SELECT FOR UPDATE ticket
  → check ticket.isAvailable() == true
  → check event.isOpenForSales() == true
  → ticket.reserve(orderId, userId, facePrice)
  → save ticket
  → evict L1 + L2 cache
  → publish ticket.reserved
  → release Redis lock
```

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| Redis lock already held (concurrent saga) | Publish `ticket.released` with reason `LOCK_CONFLICT` |
| Ticket not AVAILABLE | Publish `ticket.released` with reason `TICKET_UNAVAILABLE` |
| Event not open for sales | Publish `ticket.released` with reason `EVENT_NOT_OPEN` |
| Confirm with wrong orderId | Log and ignore (idempotency guard) |
| Release with wrong orderId | Log and skip (idempotency guard) |

---

## Dependencies

- **PostgreSQL** (`ticket_db`) — ticket and event persistence
- **Redis** — NX locks, L2 cache
- **Kafka** — command topics in, event topics out
