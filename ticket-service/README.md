# Ticket Service

**Port:** 8082

Aggregate root for the ticketing domain. Owns two bounded contexts: **Events** (scheduling
and lifecycle) and **Tickets** (inventory, seat assignments, status). All ticket-state
transitions during a purchase flow are driven by saga commands from the saga-orchestrator,
delivered on the **unified `ticket.cmd` topic**.

---

## Responsibilities

- Event CRUD and lifecycle management (DRAFT → OPEN → SALES_CLOSED → COMPLETED / CANCELLED)
- Ticket inventory management (create, update, delete seats)
- Executing saga reservation commands: **Reserve / Confirm / Release** (all on one topic)
- Publishing state-change events to Kafka for downstream consumers
- Providing internal HTTP endpoints for event status validation (used by order-service and secondary-market-service)

---

## Internal architecture

```
TicketController        EventController
      │                      │
TicketService           EventService
      │                      │
TicketRepository     EventRepository   ← ticket_db (PostgreSQL master/slave)
      │
TicketCommandConsumer (Kafka: ticket.cmd — single topic, 3 partitions, orderId key)
      │
  handleReserveCommand()    ┐
  handleConfirmCommand()    ├── dispatched by message type
  handleReleaseCommand()    ┘
      │
TicketEventPublisher (Kafka) → publishes ticket.reserved / ticket.confirmed / ticket.released
```

### Unified command topic — strict per-order ordering

All three command types arrive on the single `ticket.cmd` topic keyed by `orderId`.
Same orderId → same partition → same consumer thread → strictly sequential processing.
A Release can never overtake an in-flight Confirm because they share the partition.

### Two-layer caching for hot reads

| Layer | Technology            | Scope         | TTL  |
| ----- | --------------------- | ------------- | ---- |
| L1    | Caffeine (in-process) | per-instance  | 30 s |
| L2    | Redis                 | cross-instance | 30 s |

Read path: L1 → L2 → PostgreSQL slave. Every write evicts both L1 and L2.

### Concurrency protection — three independent layers

1. **Redis NX lock** (`ticket:lock:{ticketId}`, 30 s TTL) — acquired before any reserve
   command; released in `finally`. Coarse gate against concurrent sagas targeting the
   same ticket.
2. **`SELECT ... FOR UPDATE`** on the ticket row during reserve/confirm/release — gives
   serializable semantics within the transaction.
3. **Optimistic `@Version`** on the `Ticket` entity catches any race that slipped past
   the first two layers at flush time.
4. **UNIQUE partial index** `idx_tickets_unique_seat (event_id, section, row, seat) NULLS NOT DISTINCT`
   — final DB-level guarantee that no two tickets can share the same seat in the same event.

Even a perfectly-timed double-spend across pods cannot overbook: the UNIQUE index throws
at COMMIT regardless of which thread wins the lock race.

---

## API endpoints

### Ticket endpoints

| Method   | Path                                | Auth        | Description                       |
| -------- | ----------------------------------- | ----------- | --------------------------------- |
| `POST`   | `/api/tickets`                      | Yes (Admin) | Create a ticket                   |
| `GET`    | `/api/tickets/{id}`                 | Yes         | Get ticket by ID                  |
| `GET`    | `/api/tickets?eventId=`             | Yes         | All tickets for an event          |
| `GET`    | `/api/tickets/available?eventId=`   | Yes         | Available tickets for event       |
| `PUT`    | `/api/tickets/{id}`                 | Yes (Admin) | Update ticket                     |
| `DELETE` | `/api/tickets/{id}`                 | Yes (Admin) | Delete ticket (AVAILABLE only)    |

### Event admin endpoints

| Method  | Path                                 | Auth        | Description           |
| ------- | ------------------------------------ | ----------- | --------------------- |
| `POST`  | `/api/tickets/events`                | Yes (Admin) | Create event          |
| `PATCH` | `/api/tickets/events/{id}/open`      | Yes (Admin) | Open event for sales  |
| `PATCH` | `/api/tickets/events/{id}/cancel`    | Yes (Admin) | Cancel event          |
| `PATCH` | `/api/tickets/events/{id}/close`     | Yes (Admin) | Close sales           |
| `GET`   | `/api/tickets/events`                | Yes         | List open events      |

### Internal endpoints (not exposed by gateway)

| Method | Path                                          | Description                                                |
| ------ | --------------------------------------------- | ---------------------------------------------------------- |
| `GET`  | `/internal/events/{eventId}/status`           | Event status by eventId (used by secondary-market-service) |
| `GET`  | `/internal/tickets/{ticketId}/event-status`   | Event status via ticketId (used by order-service)          |

---

## Saga command handling

`TicketCommandConsumer` listens on the single `ticket.cmd` topic and dispatches by message type:

| Message type            | Handler                  | Action                                                                                  |
| ----------------------- | ------------------------ | --------------------------------------------------------------------------------------- |
| `TicketReserveCommand`  | `handleReserveCommand`   | Acquire Redis lock → check availability → validate event OPEN → `reserve()` → publish `ticket.reserved` or `ticket.released` |
| `TicketConfirmCommand`  | `handleConfirmCommand`   | Validate orderId matches → `confirm()` → publish `ticket.confirmed`                     |
| `TicketReleaseCommand`  | `handleReleaseCommand`   | Validate orderId matches → `release()` → publish `ticket.released`                      |

Saga compensation flows through the same `ticket.cmd` topic — the orchestrator simply
sends a `TicketReleaseCommand` on the same partition (by orderId), guaranteeing that
the release is ordered after any in-flight Confirm for the same order.

---

## Event lifecycle

```
DRAFT → OPEN → SALES_CLOSED
            ↘ CANCELLED
OPEN → COMPLETED (after event date)
```

`isOpenForSales()` returns `true` only when all of:
- status == OPEN
- `now > salesOpenAt`
- `now < salesCloseAt`
- `now < eventDate`

---

## Normal flow — Reserve

```
saga-orchestrator → ticket.cmd (TicketReserveCommand)
  ├─ acquire Redis NX lock (30 s TTL)
  ├─ SELECT FOR UPDATE ticket
  ├─ check ticket.isAvailable()
  ├─ check event.isOpenForSales()
  ├─ ticket.reserve(orderId, userId, facePrice)   — @Version++
  ├─ save ticket  → INSERT enforces UNIQUE partial seat index
  ├─ evict L1 + L2 cache
  ├─ publish ticket.reserved
  └─ release Redis lock
```

## Failure flows

| Scenario                                | Behaviour                                                         |
| --------------------------------------- | ----------------------------------------------------------------- |
| Redis lock already held (concurrent saga) | Publish `ticket.released` with reason `LOCK_CONFLICT`            |
| Ticket not AVAILABLE                    | Publish `ticket.released` with reason `TICKET_UNAVAILABLE`        |
| Event not open for sales                | Publish `ticket.released` with reason `EVENT_NOT_OPEN`            |
| Confirm with wrong orderId              | Log and ignore (idempotency guard)                                |
| Release with wrong orderId              | Log and skip (idempotency guard)                                  |
| `OptimisticLockingException` at flush   | Treated as `TICKET_UNAVAILABLE` (another saga won the race)       |

---

## Reservation expiry cleanup

A `ReservedTicketCleanupJob` scans `idx_tickets_reserved_until` (partial index on RESERVED
rows) every minute and releases tickets whose `reserved_until` has elapsed. This is the
safety net for sagas that crashed between reserve and confirm.

---

## Dependencies

- **PostgreSQL** (`ticket_db`) — ticket and event persistence + partial indexes
- **Redis** — NX locks for reservation gate + L2 cache
- **Kafka** — `ticket.cmd` in; `ticket.reserved`, `ticket.released`, `ticket.confirmed`, `event.status.changed` out
