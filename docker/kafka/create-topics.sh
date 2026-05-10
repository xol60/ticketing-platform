#!/bin/bash
set -e

KAFKA=kafka:9092
PARTITIONS=3
REPLICATION=1

# ---------------------------------------------------------------------------
# create_topic — create a topic if it doesn't exist yet.
#   $1  topic name
#   $2  partition count  (default: $PARTITIONS)
# ---------------------------------------------------------------------------
create_topic() {
  local topic=$1
  local partitions=${2:-$PARTITIONS}
  echo "Creating topic: $topic (partitions=$partitions)"
  kafka-topics --bootstrap-server $KAFKA \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor $REPLICATION
}

# ---------------------------------------------------------------------------
# ensure_partitions — idempotently raise the partition count of an existing
#   topic.  Kafka only allows increasing; alter is silently skipped when the
#   topic already has >= the requested count.
#   $1  topic name
#   $2  desired partition count  (default: $PARTITIONS)
# ---------------------------------------------------------------------------
ensure_partitions() {
  local topic=$1
  local partitions=${2:-$PARTITIONS}
  kafka-topics --bootstrap-server $KAFKA \
    --alter \
    --topic "$topic" \
    --partitions "$partitions" 2>/dev/null || true
}

echo "Waiting for Kafka to be ready..."
sleep 5

# ── Ticket topics ────────────────────────────────────────────────────────────
# Unified command topic — reserve, confirm, release all keyed by orderId so
# they land on the same partition and are consumed in order.
# 3 partitions → all three ticket-service consumer threads are active.
create_topic "ticket.cmd"
create_topic "ticket.reserved"
create_topic "ticket.released"
create_topic "ticket.confirmed"
# Legacy split topics kept so existing offsets / DLQ consumers aren't orphaned;
# new code never produces to these but they must exist for broker compatibility.
create_topic "ticket.reserve.cmd"
create_topic "ticket.release.cmd"
create_topic "ticket.confirm.cmd"

# ── Order topics ─────────────────────────────────────────────────────────────
create_topic "order.created"
create_topic "order.confirmed"
create_topic "order.failed"
create_topic "order.cancelled"
create_topic "order.price.changed"
create_topic "order.price.confirm"
create_topic "order.price.cancel"

# ── Pricing topics ───────────────────────────────────────────────────────────
create_topic "pricing.lock.cmd"
create_topic "pricing.locked"
create_topic "pricing.price.changed"
create_topic "pricing.failed"
create_topic "price.updated"
create_topic "pricing.unlock.cmd"

# ── Payment topics ───────────────────────────────────────────────────────────
# Unified command topic — charge and cancel keyed by orderId → same partition.
# 3 partitions → all three payment-service consumer threads are active.
create_topic "payment.cmd"
create_topic "payment.succeeded"
create_topic "payment.refunded"
create_topic "payment.failed"
create_topic "payment.dlq" 1        # DLQ stays single-partition for ordering
create_topic "payment.charge.cmd"   # Legacy: kept for broker compatibility

# ── Saga ─────────────────────────────────────────────────────────────────────
create_topic "saga.compensate"

# ── Reservation ──────────────────────────────────────────────────────────────
create_topic "reservation.promoted"

# ── Notification ─────────────────────────────────────────────────────────────
create_topic "notification.send"

# ── Security — single partition to preserve per-user ordering ────────────────
create_topic "auth.security.alert" 1

# ── Event lifecycle ───────────────────────────────────────────────────────────
create_topic "event.status.changed"

# ── Flash sale ────────────────────────────────────────────────────────────────
create_topic "sale.flash"

# ---------------------------------------------------------------------------
# Upgrade existing topics whose partition count was previously 1.
# ensure_partitions is a no-op when the topic already has >= the target count.
# This block is only relevant when reusing a Kafka volume from an older deploy.
# ---------------------------------------------------------------------------
echo "Ensuring partition counts on existing topics..."

# Critical saga-flow topics — must match concurrency=3 in each service
for topic in \
    ticket.cmd ticket.reserved ticket.released ticket.confirmed \
    order.created order.confirmed order.failed order.cancelled \
    order.price.changed order.price.confirm order.price.cancel \
    pricing.lock.cmd pricing.locked pricing.price.changed pricing.failed \
    pricing.unlock.cmd price.updated \
    payment.cmd payment.succeeded payment.refunded payment.failed \
    saga.compensate reservation.promoted notification.send \
    event.status.changed sale.flash \
    ticket.reserve.cmd ticket.release.cmd ticket.confirm.cmd \
    payment.charge.cmd; do
  ensure_partitions "$topic" 3
done

# Single-partition topics — do NOT alter these
# payment.dlq       — ordering guarantee requires 1 partition
# auth.security.alert — per-user ordering requires 1 partition

echo "All topics created/verified successfully"
kafka-topics --bootstrap-server $KAFKA --list
