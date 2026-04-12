# Notification Service

**Port:** 8089

Sends user-facing notifications (email / push) in response to Kafka events. Purely reactive — it has no inbound HTTP API for user calls. Also receives admin alerts for payment DLQ events.

---

## Responsibilities

- Send confirmation notifications when a ticket purchase is confirmed
- Send failure notifications when a payment permanently fails
- Handle generic notification commands (`notification.send`)
- Persist notification logs for audit and deduplication

---

## Internal Architecture

```
NotificationEventConsumer (Kafka)
  → ticket.confirmed   → NotificationService.sendTicketConfirmed()
  → payment.dlq        → NotificationService.sendAdminAlert()
  → notification.send  → NotificationService.sendGeneric()
```

All handlers are idempotent — if the same `sagaId` or `orderId` has already been notified, the duplicate is logged and dropped.

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `ticket.confirmed` | Subscribe | Send purchase confirmation to buyer |
| `payment.dlq` | Subscribe | Send admin alert for unrecoverable payment failure |
| `notification.send` | Subscribe | Generic notification command from any service |

---

## Normal Flow — Purchase Confirmation

```
ticket.confirmed { sagaId, ticketId, orderId, userId }
  → look up user email / push token
  → format confirmation message with ticket + order details
  → send via configured channel (email / push)
  → save NotificationLog { userId, type: TICKET_CONFIRMED, sentAt }
```

## Normal Flow — Admin Alert

```
payment.dlq { orderId, userId, reason, amount }
  → format admin alert with payment failure details
  → send to admin email / Slack webhook
  → save NotificationLog { type: ADMIN_ALERT, ... }
```

---

## Failure Flows

| Scenario | Behaviour |
|----------|-----------|
| Notification provider unavailable | Retry with exponential backoff (Kafka manual ack) |
| Duplicate event (same sagaId) | Idempotency check → skip and ack |
| Missing user contact info | Log warning, ack message (don't block) |

---

## Notification Log Schema

```
id, userId, sagaId, orderId
type: TICKET_CONFIRMED | PAYMENT_FAILED | ADMIN_ALERT | GENERIC
channel: EMAIL | PUSH | SLACK
status: SENT | FAILED
createdAt
```

---

## Dependencies

- **PostgreSQL** (`notification_db`) — notification log
- **Kafka** — consume `ticket.confirmed`, `payment.dlq`, `notification.send`
- **Email provider / Push provider** — configured via `notification.email.*` / `notification.push.*`
