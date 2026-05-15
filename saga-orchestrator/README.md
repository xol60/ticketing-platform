# Saga Orchestrator

**Port:** 8084

Drives the distributed purchase transaction using the **orchestration saga pattern**.
Every step is a Kafka command/response pair. State is **write-through to Postgres + Redis**
(Postgres is durable, Redis is the fast read cache). A scheduled watchdog detects and
compensates stuck sagas.

---

## Responsibilities

- Start a new saga when `order.created` arrives
- Drive the saga forward step-by-step via Kafka commands
- React to success/failure events from each participant
- Trigger compensation (rollback) in reverse order on any failure
- Detect and recover stuck sagas via a scheduled watchdog (partial-index assisted)

---

## Saga state machine

```
STARTED
  └─ send TicketReserveCommand on ticket.cmd
TICKET_RESERVED
  └─ send PriceLockCommand on pricing.lock.cmd
PRICING_LOCKED
  └─ send PaymentChargeCommand on payment.cmd
PAYMENT_CHARGED
  └─ send TicketConfirmCommand on ticket.cmd
COMPLETED  ✓  (terminal)
  └─ publish OrderConfirmedEvent on order.confirmed

AWAITING_PRICE_CONFIRMATION  (Case C from pricing-service)
  ├─ user confirms     → re-issue PriceLockCommand{confirmed=true} → continue
  └─ user declines OR 30s watchdog timeout → compensate

CANCELLED ✗  (terminal — compensation path)
  └─ send TicketReleaseCommand on ticket.cmd (same unified topic, same orderId key)
     so the release is ordered AFTER any in-flight confirm
  └─ if payment already charged: send PaymentCancelCommand on payment.cmd (refund)
  └─ publish OrderCancelledEvent on order.cancelled

FAILED ✗  (terminal — pricing/ticket validation failed before any side-effect)
  └─ publish OrderFailedEvent on order.failed
```

State is persisted as JSON in Postgres `saga_states` (durable source of truth) and
mirrored to Redis `saga:{sagaId}` with 10-minute TTL for fast reads.

---

## Internal architecture

```
OrderEventConsumer         (Kafka: order.created, order.price.confirm, order.price.cancel)
  └─ SagaOrchestrator.startSaga() / onPriceConfirm() / onPriceCancel()
        ├─ load/persist saga state (Postgres first, Redis cache write-through)
        └─ publish next command on the appropriate Kafka topic

TicketEventConsumer        (Kafka: ticket.reserved, ticket.released, ticket.confirmed)
PricingEventConsumer       (Kafka: pricing.locked, pricing.price.changed, pricing.failed)
PaymentEventConsumer       (Kafka: payment.succeeded, payment.failed, payment.refunded)

  Each consumer calls SagaOrchestrator.onXxx()
        ├─ load saga state
        ├─ verify expected status (idempotency guard)
        ├─ transition state machine
        └─ send next command OR start compensation

SagaWatchdog (Spring @Scheduled, fixedDelay = 30s)
  └─ findByStatusNotInAndUpdatedAtBefore(TERMINAL, threshold)
     uses partial index idx_saga_states_active_stale — query returns 0 rows
     in healthy state, scan terminates immediately. Stuck sagas hit compensateSaga().
```

---

## Kafka topics

| Topic                   | Direction | Carries                                                              |
| ----------------------- | --------- | -------------------------------------------------------------------- |
| `order.created`         | Subscribe | OrderCreatedEvent — saga ignition                                    |
| `order.price.confirm`   | Subscribe | OrderPriceConfirmCommand — user accepted new price                   |
| `order.price.cancel`    | Subscribe | OrderPriceCancelCommand — user rejected new price                    |
| `ticket.cmd`            | Publish   | TicketReserve / Confirm / Release Command (unified topic, orderId key) |
| `ticket.reserved`       | Subscribe | TicketReservedEvent                                                  |
| `ticket.released`       | Subscribe | TicketReleasedEvent (also signals admin-initiated release)           |
| `ticket.confirmed`      | Subscribe | TicketConfirmedEvent — terminal step                                 |
| `pricing.lock.cmd`      | Publish   | PriceLockCommand (`confirmed=false` first; `true` after user accept) |
| `pricing.locked`        | Subscribe | PricingLockedEvent                                                   |
| `pricing.price.changed` | Subscribe | PriceChangedEvent → pause in AWAITING_PRICE_CONFIRMATION             |
| `pricing.failed`        | Subscribe | PricingFailedEvent (fabricated/invalid price)                        |
| `payment.cmd`           | Publish   | PaymentCharge / PaymentCancel Command (unified topic, orderId key)   |
| `payment.succeeded`     | Subscribe | PaymentSucceededEvent                                                |
| `payment.failed`        | Subscribe | PaymentFailedEvent                                                   |
| `payment.refunded`      | Subscribe | PaymentRefundedEvent (after PaymentCancel)                           |
| `order.confirmed`       | Publish   | OrderConfirmedEvent — saga COMPLETED                                 |
| `order.failed`          | Publish   | OrderFailedEvent — saga FAILED (no side effects to compensate)       |
| `order.cancelled`       | Publish   | OrderCancelledEvent — saga CANCELLED (after compensation)            |
| `order.price.changed`   | Publish   | OrderPriceChangedEvent — propagates Case C to order-service          |

> **Note:** the previous `saga.compensate` topic and split `ticket.reserve.cmd` /
> `ticket.confirm.cmd` / `ticket.release.cmd` topics were merged into the single
> `ticket.cmd` topic. Same partition + orderId key guarantees per-order ordering,
> so a Release can never overtake an in-flight Confirm.

---

## Normal flow

```
order.created             → startSaga()            → ticket.cmd  (Reserve)
ticket.reserved           → onTicketReserved()     → pricing.lock.cmd
pricing.locked            → onPricingLocked()      → payment.cmd (Charge)
payment.succeeded         → onPaymentSucceeded()   → ticket.cmd  (Confirm)
ticket.confirmed          → onTicketConfirmed()    → order.confirmed
```

## Failure flows

### Pricing rejects (fabricated price)

```
pricing.failed → compensateSaga()
              └─ ticket.cmd Release (same partition, ordered after any pending Confirm)
              └─ order.cancelled
```

### Price changed (Case C — user must confirm)

```
pricing.price.changed → saga → AWAITING_PRICE_CONFIRMATION
                              ├─ user confirms → pricing.lock.cmd (confirmed=true) → resume
                              └─ user cancels OR 30s SagaWatchdog timeout → compensateSaga()
```

### Payment fails after retries (DLQ)

```
payment.failed → compensateSaga()
              └─ ticket.cmd Release
              └─ order.cancelled
              └─ payment-service also publishes to payment.dlq for admin alert
```

### Ticket released externally during PRICING_LOCKED

```
ticket.released (admin override) → compensateSaga()
              └─ payment.cmd Cancel (in case payment is in-flight)
              └─ order.failed
```

### Stuck saga (watchdog)

```
SagaWatchdog @Scheduled (30s)
  └─ SELECT * FROM saga_states
     WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')
       AND updated_at < now() - INTERVAL '60s'
     /* uses partial index idx_saga_states_active_stale */
  └─ compensateSaga(reason='SAGA_STUCK')
```

The query is O(stuck) instead of O(N): the partial index excludes terminal rows,
so under healthy load the scan returns 0 rows and terminates immediately.

### Idempotency

Each state transition checks `currentStatus` against the expected status before
acting. Out-of-order or duplicate events (from Kafka at-least-once delivery) are
logged and dropped — Postgres is the single source of truth.

---

## Persistence schema

**Postgres `saga_states`** (durable):

```sql
saga_id     VARCHAR(36) PRIMARY KEY
status      VARCHAR(30) NOT NULL     -- state-machine state
state_json  TEXT        NOT NULL     -- full SagaState as JSON
created_at  TIMESTAMPTZ NOT NULL
updated_at  TIMESTAMPTZ NOT NULL

-- Partial indexes for performance
idx_saga_states_active_status   ON (status) WHERE status NOT IN (terminal)
idx_saga_states_active_stale    ON (updated_at) WHERE status NOT IN (terminal)
```

**Redis `saga:{sagaId}`** (acceleration):

- Type: String (serialized JSON)
- TTL: 10 minutes
- Written through after every Postgres commit
- On a Redis miss, reads fall back to Postgres and repopulate the cache

---

## Dependencies

- **Postgres** — durable saga state (single source of truth)
- **Redis** — write-through cache for fast saga lookups
- **Kafka** — all communication; no direct HTTP between services
