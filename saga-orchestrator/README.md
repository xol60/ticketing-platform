# Saga Orchestrator

**Port:** 8084

Drives the distributed purchase transaction using the **orchestration saga pattern**. Each step is a Kafka command/response pair. State is stored in Redis. A watchdog timer compensates stuck sagas automatically.

---

## Responsibilities

- Start a new saga when `order.created` arrives
- Drive the saga forward step-by-step via Kafka commands
- React to success/failure events from each participant
- Trigger compensation (rollback) in reverse order on any failure
- Detect and recover stuck sagas via a scheduled watchdog

---

## Saga State Machine

```
STARTED
  → send ticket.reserve.cmd
TICKET_RESERVED
  → send pricing.lock.cmd
PRICE_LOCKED
  → send payment.charge.cmd
PAYMENT_SUCCEEDED
  → send ticket.confirm.cmd
CONFIRMED  ✓  (terminal)
  → publish order.confirmed

On any failure:
COMPENSATING
  → send ticket.release.cmd   (if ticket was reserved)
  → send pricing.unlock.cmd   (if price was locked)
  → publish order.failed
FAILED  ✗  (terminal)
```

State is persisted as a JSON hash in Redis: `saga:{sagaId}` with a TTL of several minutes.

---

## Internal Architecture

```
OrderEventConsumer         (Kafka: order.created)
  → SagaOrchestrator.startSaga()
      → save saga state in Redis (STARTED)
      → publish ticket.reserve.cmd

TicketEventConsumer        (Kafka: ticket.reserved, ticket.released, ticket.confirmed)
PricingEventConsumer       (Kafka: pricing.locked)
PaymentEventConsumer       (Kafka: payment.succeeded, payment.failed)

  Each consumer calls SagaOrchestrator.onXxx()
      → load saga state from Redis
      → transition state
      → send next command or compensate
      → save updated state

SagaWatchdog (scheduled every 30s)
  → scan Redis for sagas in non-terminal states with age > threshold
  → trigger compensateSaga()
```

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `order.created` | Subscribe | Start saga |
| `ticket.reserve.cmd` | Publish | Ask ticket-service to reserve |
| `ticket.reserved` | Subscribe | Proceed to pricing |
| `ticket.released` | Subscribe | Ticket reservation failed/released |
| `ticket.confirm.cmd` | Publish | Ask ticket-service to confirm |
| `ticket.confirmed` | Subscribe | Saga complete |
| `ticket.release.cmd` | Publish | Compensation: release reserved ticket |
| `pricing.lock.cmd` | Publish | Ask pricing-service to lock price |
| `pricing.locked` | Subscribe | Proceed to payment |
| `pricing.unlock.cmd` | Publish | Compensation: release locked price |
| `payment.charge.cmd` | Publish | Ask payment-service to charge |
| `payment.succeeded` | Subscribe | Proceed to confirm |
| `payment.failed` | Subscribe | Compensate |
| `order.confirmed` | Publish | Notify order-service of success |
| `order.failed` | Publish | Notify order-service of failure |
| `saga.compensate` | Publish | Broadcast compensation event |

---

## Normal Flow

```
order.created → startSaga() → ticket.reserve.cmd
ticket.reserved → onTicketReserved() → pricing.lock.cmd
pricing.locked → onPricingLocked() → payment.charge.cmd
payment.succeeded → onPaymentSucceeded() → ticket.confirm.cmd
ticket.confirmed → onTicketConfirmed() → order.confirmed
```

## Failure Flows

### Payment fails

```
payment.failed
  → compensateSaga()
  → ticket.release.cmd → ticket released
  → pricing.unlock.cmd → price unlocked
  → order.failed
```

### Ticket unavailable

```
ticket.released (reason=TICKET_UNAVAILABLE or EVENT_NOT_OPEN)
  → compensateSaga()
  → (no ticket to release — already released by ticket-service)
  → order.failed
```

### Stuck saga (watchdog)

```
Watchdog detects saga age > threshold, still in non-terminal state
  → compensateSaga() with reason=TIMEOUT
  → publishes saga.compensate (ticket-service handles release if needed)
  → order.failed
```

### Duplicate events (idempotency)

Each state transition checks the current saga status. If the incoming event doesn't match the expected step, it is logged and dropped. Redis state is the source of truth.

---

## Redis State Schema

```
Key:   saga:{sagaId}
Type:  Hash
Fields:
  sagaId, orderId, userId, ticketId
  status: STARTED | TICKET_RESERVED | PRICE_LOCKED | PAYMENT_SUCCEEDED | CONFIRMED | COMPENSATING | FAILED
  lockedPrice
  traceId
  createdAt, updatedAt
TTL: configurable (e.g. 10 minutes)
```

---

## Dependencies

- **Redis** — saga state store
- **Kafka** — all communication is event-driven; no direct HTTP calls
