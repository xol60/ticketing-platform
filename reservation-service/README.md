# Reservation Service

**Port:** 8086

Manages fair queuing for high-demand tickets. When a ticket becomes available (e.g. after a reservation is released), the next user in the queue is automatically promoted and notified via Kafka.

---

## Responsibilities

- Allow users to join the waitlist for a specific ticket
- Maintain queue order fairly (FIFO by join timestamp)
- When a ticket is released, promote the next queued user
- Expire stale reservations (users who no longer respond) on a schedule
- Publish `reservation.promoted` events so order-service can auto-create an order for the promoted user

---

## Internal Architecture

```
ReservationController
  → ReservationService
      ├── joinQueue(ticketId, userId)
      ├── leaveQueue(reservationId, userId)
      ├── getQueuePosition(ticketId, userId)
      ├── promoteNextFromQueue(TicketReleasedEvent)  [Kafka consumer]
      └── expireOldReservations()                    [scheduled]

ReservationEventConsumer (Kafka)
  → ticket.released → promoteNextFromQueue()
```

### Queue implementation

Each ticket has a Redis sorted set keyed `queue:{ticketId}`.
- **Score** = join timestamp (epoch milliseconds) — ensures FIFO ordering
- **Member** = `{userId}:{reservationId}`

On `ticket.released`:
1. `ZRANGE queue:{ticketId} 0 0` — peek the head of the queue
2. `ZREM` to atomically pop the user
3. Save promoted user in DB
4. Publish `reservation.promoted { ticketId, userId, reservationId }`

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/reservations/{ticketId}/join` | Yes | Join the queue for a ticket |
| `DELETE` | `/api/reservations/{id}` | Yes | Leave the queue |
| `GET` | `/api/reservations/{ticketId}/position` | Yes | Get current queue position |

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `ticket.released` | Subscribe | Trigger promotion of next queued user |
| `reservation.promoted` | Publish | Notify that a user has been promoted from queue |

---

## Normal Flow — Queue Join

```
POST /api/reservations/{ticketId}/join
  → Check user not already in queue
  → Save Reservation entity (QUEUED status)
  → ZADD queue:{ticketId} score=timestamp member=userId:reservationId
  → Return { reservationId, position }
```

## Normal Flow — Promotion

```
Kafka: ticket.released { ticketId }
  → ZRANGE + ZREM queue:{ticketId}
  → Update reservation status → PROMOTED
  → Publish reservation.promoted { ticketId, userId }
  → order-service can consume this and auto-create order on behalf of the user
```

---

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| User already in queue | `409 Conflict` |
| Queue empty when ticket released | No promotion — ticket returns to public pool |
| Promoted user doesn't respond | `expireOldReservations()` marks EXPIRED, pops next |
| Redis unavailable | Queue operations fail; reservation saved to DB as fallback |

---

## Dependencies

- **PostgreSQL** (`reservation_db`) — reservation records
- **Redis** — sorted set queue per ticket
- **Kafka** — subscribe to `ticket.released`; publish `reservation.promoted`
