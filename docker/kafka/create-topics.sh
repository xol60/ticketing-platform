#!/bin/bash
set -e

# ============================================================================
#  Kafka topic provisioning for the ticketing platform
# ============================================================================
#  Runs once at stack startup (kafka-init service in docker-compose).
#  Idempotent: safe to re-run; --if-not-exists skips existing topics, and the
#  ensure_partitions block at the end only raises partition counts (never
#  lowers — Kafka doesn't allow that).
#
#  ── Partitioning strategy ──────────────────────────────────────────────────
#  Every saga-flow topic has 3 partitions to match concurrency=3 on each
#  consumer.  Records are keyed by orderId, so all events for a given order
#  land on the same partition and are processed sequentially by one consumer
#  thread — strict per-order ordering without application-level locking.
#
#  Two topics keep 1 partition on purpose:
#    • payment.dlq         — chronological DLQ replay across all orders
#    • auth.security.alert — global ordering for security forensics
#
#  ── Unified command topics ─────────────────────────────────────────────────
#  Ticket and payment services each accept ALL commands on one topic, not
#  one topic per command type.  See README for the full rationale; the short
#  version is:
#    ticket.cmd   carries Reserve / Confirm / Release   keyed by orderId
#    payment.cmd  carries Charge / Cancel               keyed by orderId
#  This way a Release can never overtake its preceding Reserve, and a Cancel
#  can never overtake its preceding Charge — they share a partition.
# ============================================================================

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

# ── Ticket domain ────────────────────────────────────────────────────────────
# Commands (unified):
create_topic "ticket.cmd"                 # Reserve / Confirm / Release, keyed by orderId
# Events:
create_topic "ticket.reserved"
create_topic "ticket.released"
create_topic "ticket.confirmed"

# ── Order domain ─────────────────────────────────────────────────────────────
create_topic "order.created"
create_topic "order.confirmed"
create_topic "order.failed"
create_topic "order.cancelled"
create_topic "order.price.changed"
create_topic "order.price.confirm"
create_topic "order.price.cancel"

# ── Pricing domain ───────────────────────────────────────────────────────────
create_topic "pricing.lock.cmd"
create_topic "pricing.unlock.cmd"
create_topic "pricing.locked"
create_topic "pricing.price.changed"
create_topic "pricing.failed"
create_topic "price.updated"              # keyed by eventId — fan-out to SSE clients

# ── Payment domain ───────────────────────────────────────────────────────────
# Commands (unified):
create_topic "payment.cmd"                # Charge / Cancel, keyed by orderId
# Events:
create_topic "payment.succeeded"
create_topic "payment.refunded"
create_topic "payment.failed"
create_topic "payment.dlq" 1              # 1 partition — chronological DLQ replay

# ── Saga orchestration ──────────────────────────────────────────────────────
create_topic "saga.compensate"

# ── Reservation queue ───────────────────────────────────────────────────────
create_topic "reservation.promoted"       # keyed by ticketId

# ── Notification ─────────────────────────────────────────────────────────────
create_topic "notification.send"

# ── Auth / security ──────────────────────────────────────────────────────────
create_topic "auth.security.alert" 1      # 1 partition — global ordering for forensics

# ── Event lifecycle ──────────────────────────────────────────────────────────
create_topic "event.status.changed"       # keyed by eventId

# ── Flash sale ───────────────────────────────────────────────────────────────
create_topic "sale.flash"                 # keyed by eventId

# ---------------------------------------------------------------------------
# Upgrade existing topics whose partition count was previously 1.
# ensure_partitions is a no-op when the topic already has >= the target count.
# Only relevant when reusing a Kafka volume from an older deploy.
# ---------------------------------------------------------------------------
echo "Ensuring partition counts on existing topics..."

# Critical saga-flow topics — must match concurrency=3 in each service
for topic in \
    ticket.cmd ticket.reserved ticket.released ticket.confirmed \
    order.created order.confirmed order.failed order.cancelled \
    order.price.changed order.price.confirm order.price.cancel \
    pricing.lock.cmd pricing.unlock.cmd pricing.locked \
    pricing.price.changed pricing.failed price.updated \
    payment.cmd payment.succeeded payment.refunded payment.failed \
    saga.compensate reservation.promoted notification.send \
    event.status.changed sale.flash; do
  ensure_partitions "$topic" 3
done

# Single-partition topics — do NOT alter these:
#   payment.dlq         — chronological DLQ replay
#   auth.security.alert — global ordering for forensics

echo "All topics created/verified successfully"
kafka-topics --bootstrap-server $KAFKA --list
