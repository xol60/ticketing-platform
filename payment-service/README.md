# Payment Service

**Port:** 8087

Handles payment charging and refunds as part of the saga flow. The Kafka consumer thread
never blocks on the external gateway — a dedicated **`PaymentRetryWatchdog`** scheduler
calls the gateway off-thread and retries with backoff. On unrecoverable failure, the event
is routed to a Dead Letter Queue. All operations are idempotent.

---

## Responsibilities

- Receive `PaymentChargeCommand` and `PaymentCancelCommand` on the unified `payment.cmd` topic
- Persist payment intent quickly (consumer thread returns in ~3 ms)
- Charge / refund via the external gateway from a dedicated scheduler thread
- Publish `payment.succeeded` / `payment.failed` / `payment.refunded` back to the saga
- Route unrecoverable failures (after retries exhausted) to `payment.dlq` + admin alert

---

## Internal architecture

```
PaymentCommandConsumer  (Kafka: payment.cmd)
  └─ PaymentService.processPayment(cmd)        — Charge command
        ├─ idempotency check (existing payment for orderId?)
        ├─ INSERT payments { status=PENDING, nextRetryAt=now }
        └─ return ~3 ms  →  consumer thread released

  └─ PaymentService.cancelPayment(cmd)         — Cancel command
        ├─ load payment; if SUCCESS → refund via gateway → publish payment.refunded
        └─ if still PENDING → mark CANCELLATION_REQUESTED → watchdog won't pick it up

PaymentRetryWatchdog  (Spring @Scheduled, fixedDelay = 2s)
  └─ findDueForRetry(now, PageRequest.of(0, 50))
     uses partial index idx_payments_retry_due — O(due) instead of O(all-pending)
  └─ for each due payment:
        ├─ TX 1: claim-lease (set nextRetryAt = now + 30s) — survives pod crash
        ├─ gateway.charge() / gateway.refund()  — off Kafka thread
        └─ TX 2: SUCCESS → publish payment.succeeded
                 FAILURE with attempts < MAX → schedule next backoff (5s, 15s)
                 FAILURE with attempts ≥ MAX → mark FAILED + payment.dlq + admin alert
```

### Why the watchdog matters

The original design called `gateway.charge() + Thread.sleep(retry_backoff)` directly
inside the Kafka consumer thread. With 3 consumer threads (`concurrency=3`) blocking
on 1–7 seconds of sleep, the **entire `payment.cmd` partition froze**. After moving the
gateway call to the watchdog:

| Metric                          | Before    | After    |
| ------------------------------- | --------- | -------- |
| Per-partition consumer throughput | ~15 msg/s | ~300 msg/s |
| Tail-latency p99 (other orders) | 5–7 s     | < 200 ms |

### Claim-lease pattern

When the watchdog picks up a payment, it first sets `nextRetryAt = now + 30s` in its own
transaction. This is a **lease** — if the pod crashes mid-charge, no other replica will
pick up the same row until 30 seconds elapse. Combined with `@Version` optimistic locking,
this prevents double-charging across multiple payment-service replicas.

### Idempotency

Before charging, the service checks whether a payment record already exists for `orderId`:
- `SUCCESS`  → re-publish `payment.succeeded` (safe replay, no new charge)
- `FAILED`   → re-publish `payment.failed`
- `PENDING`  → no-op (watchdog will pick it up on schedule)
- `REFUNDED` → re-publish `payment.refunded`

This handles Kafka at-least-once delivery without double-charging.

### DLQ (Dead Letter Queue)

After `MAX_ATTEMPTS=3` failures (configurable backoff: 5 s, 15 s), the payment is marked
FAILED and the event is republished to `payment.dlq` (single partition for global
chronological replay). `notification-service` consumes the DLQ and sends an admin alert.

---

## API endpoints

| Method | Path                            | Auth | Description                   |
| ------ | ------------------------------- | ---- | ----------------------------- |
| `GET`  | `/api/payments/order/{orderId}` | Yes  | Get payment status by order   |

---

## Kafka topics

| Topic               | Direction | Carries                                            | Partitions |
| ------------------- | --------- | -------------------------------------------------- | ---------- |
| `payment.cmd`       | Subscribe | PaymentChargeCommand / PaymentCancelCommand (unified, orderId key) | 3 |
| `payment.succeeded` | Publish   | PaymentSucceededEvent (with PAY-xxx reference)     | 3          |
| `payment.failed`    | Publish   | PaymentFailedEvent — saga compensates              | 3          |
| `payment.refunded`  | Publish   | PaymentRefundedEvent (after Cancel)                | 3          |
| `payment.dlq`       | Publish   | PaymentFailedEvent (retries exhausted)             | **1**      |
| `notification.send` | Publish   | NotificationSendCommand (admin alert on DLQ)       | 3          |

Single-partition `payment.dlq` preserves strict chronological ordering across all orders
so admins can reconstruct the failure timeline.

---

## Normal flow

```
1. Saga → payment.cmd PaymentChargeCommand { sagaId, orderId, userId, amount }
2. Consumer thread: idempotency check, INSERT payments(PENDING, nextRetryAt=now), return
3. Watchdog tick (within ~2 s):
     ├─ claim lease (UPDATE nextRetryAt = now + 30s)
     ├─ gateway.charge(amount) → returns { transactionId=PAY-xxx, status=SUCCESS }
     └─ UPDATE payments SET status=SUCCESS, payment_reference=PAY-xxx
4. Publish payment.succeeded → saga proceeds to confirm ticket
```

## Failure flows

### Retryable failure (network timeout, gateway 5xx)

```
gateway returns timeout or 5xx → UPDATE attempt_count++, nextRetryAt = now + backoff
  → watchdog retries up to 3 times (5s, 15s)
  → final retry fails → publish payment.failed + payment.dlq + admin notification
```

### Unrecoverable failure (card permanently declined, fraud block)

Currently treated the same as retryable. The 3-retry budget burns through quickly when
the failure is deterministic; production version could classify 4xx vs 5xx and short-circuit.

### Refund (saga compensation after charge)

```
Saga → payment.cmd PaymentCancelCommand
  → if payment SUCCESS → gateway.refund(reference) → UPDATE status=REFUNDED → publish payment.refunded
  → if payment PENDING → mark CANCELLATION_REQUESTED → watchdog skips it
```

### Duplicate command (Kafka redelivery)

```
PaymentChargeCommand arrives again for same orderId
  → existing payment found in SUCCESS state
  → re-publish payment.succeeded (no new gateway call)
```

---

## Payment record schema

```sql
id                  UUID PRIMARY KEY
order_id            VARCHAR(36) NOT NULL
user_id             VARCHAR(36) NOT NULL
saga_id             VARCHAR(36)                        -- for watchdog → publisher path
trace_id            VARCHAR(64)
amount              NUMERIC(19,2)
currency            VARCHAR(3)
status              VARCHAR(30)                        -- PENDING | SUCCESS | FAILED | CANCELLATION_REQUESTED | REFUNDED
attempt_count       INT  DEFAULT 0
next_retry_at       TIMESTAMPTZ                        -- claim-lease window
payment_reference   VARCHAR(128)                       -- external transaction ID
failure_reason      VARCHAR(255)
version             BIGINT                             -- @Version optimistic lock
created_at, updated_at TIMESTAMPTZ

-- Partial indexes
idx_payments_retry_due  ON (next_retry_at) WHERE status = 'PENDING'   -- watchdog query
idx_payments_pending    ON (created_at)    WHERE status = 'PENDING'   -- admin view
idx_payments_success    ON (created_at DESC) WHERE status = 'SUCCESS' -- revenue reports
```

---

## Dependencies

- **PostgreSQL** (`payment_db`) — payment records + partial indexes
- **Kafka** — consume `payment.cmd`; publish `payment.succeeded`, `payment.failed`, `payment.refunded`, `payment.dlq`, `notification.send`
- **External payment gateway** — configured via `payment.gateway.url` / API key
