# Payment Service

**Port:** 8087

Handles payment charging as part of the saga flow. Integrates with an external payment provider. On failure, routes to a Dead Letter Queue (DLQ) for retry and admin alerting. All operations are idempotent — duplicate charge commands for the same orderId are safely ignored.

---

## Responsibilities

- Receive `payment.charge.cmd` from the saga-orchestrator
- Charge the user via the configured payment gateway
- Publish `payment.succeeded` or `payment.failed` back to the saga
- Route unrecoverable failures to `payment.dlq`
- Persist payment records for auditing and status reads

---

## Internal Architecture

```
PaymentCommandConsumer (Kafka: payment.charge.cmd)
  → PaymentService.processPayment(cmd)
      ├── Idempotency check: payment already processed for orderId?
      ├── Call external payment gateway
      ├── On success:
      │     → save Payment (SUCCEEDED)
      │     → publish payment.succeeded { sagaId, orderId, paymentRef, amount }
      └── On failure:
            → save Payment (FAILED)
            → if retryable: publish payment.failed
            → if unrecoverable: publish payment.dlq
```

### Idempotency

Before charging, the service checks whether a payment record already exists for `orderId`. If it does:
- `SUCCEEDED` → re-publish `payment.succeeded` (safe replay)
- `FAILED` → re-publish `payment.failed`

This handles duplicate deliveries from Kafka without double-charging.

### DLQ (Dead Letter Queue)

If the payment gateway returns an unrecoverable error (e.g. card permanently declined, fraud block), the event is published to `payment.dlq`. The notification-service consumes this topic and sends an admin alert.

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/payments/order/{orderId}` | Yes | Get payment status by order |

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `payment.charge.cmd` | Subscribe | Saga instruction to charge |
| `payment.succeeded` | Publish | Payment went through |
| `payment.failed` | Publish | Payment failed (saga will compensate) |
| `payment.dlq` | Publish | Unrecoverable failure — needs admin attention |

---

## Normal Flow

```
saga sends payment.charge.cmd { sagaId, orderId, userId, amount }
  → idempotency check: no existing payment for orderId
  → call external gateway with amount + userId
  → gateway returns { transactionId, status: SUCCESS }
  → save Payment { orderId, transactionId, amount, SUCCEEDED }
  → publish payment.succeeded { sagaId, orderId, paymentRef: transactionId, amount }
  → saga proceeds to confirm ticket
```

## Failure Flows

### Retryable failure (network timeout, gateway 5xx)

```
gateway returns timeout or 5xx
  → save Payment (FAILED)
  → publish payment.failed { reason: "GATEWAY_TIMEOUT" }
  → saga-orchestrator compensates: releases ticket, unlocks price, marks order FAILED
```

### Unrecoverable failure (card declined, fraud block)

```
gateway returns 4xx (card invalid / fraud)
  → save Payment (FAILED)
  → publish payment.failed (saga compensates)
  → publish payment.dlq (admin alert via notification-service)
```

### Duplicate command

```
payment.charge.cmd arrives again for same orderId
  → existing SUCCEEDED payment found
  → re-publish payment.succeeded (no new charge)
```

---

## Payment Record Schema

```
id, orderId, userId, sagaId
amount, currency
status: PENDING | SUCCEEDED | FAILED
paymentReference (external transaction ID)
failureReason
createdAt, updatedAt
```

---

## Dependencies

- **PostgreSQL** (`payment_db`) — payment records
- **Kafka** — consume `payment.charge.cmd`; publish `payment.succeeded`, `payment.failed`, `payment.dlq`
- **External payment gateway** — configured via `payment.gateway.url` / API key
