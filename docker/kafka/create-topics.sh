#!/bin/bash
set -e

KAFKA=kafka:9092
PARTITIONS=3
REPLICATION=1

create_topic() {
  local topic=$1
  local partitions=${2:-$PARTITIONS}
  echo "Creating topic: $topic"
  kafka-topics --bootstrap-server $KAFKA \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor $REPLICATION
}

echo "Waiting for Kafka to be ready..."
sleep 5

# Ticket topics
# Unified command topic — reserve, confirm, release all keyed by orderId so they
# land on the same partition and are consumed in order (replaces split cmd topics).
create_topic "ticket.cmd"
create_topic "ticket.reserved"
create_topic "ticket.released"
create_topic "ticket.confirmed"
# Legacy split topics kept so existing offsets / DLQ consumers aren't orphaned;
# new code never produces to these but they must exist for broker compatibility.
create_topic "ticket.reserve.cmd"
create_topic "ticket.release.cmd"
create_topic "ticket.confirm.cmd"

# Order topics
create_topic "order.created"
create_topic "order.confirmed"
create_topic "order.failed"
create_topic "order.cancelled"
create_topic "order.price.changed"
create_topic "order.price.confirm"
create_topic "order.price.cancel"

# Pricing topics
create_topic "pricing.lock.cmd"
create_topic "pricing.locked"
create_topic "pricing.price.changed"
create_topic "pricing.failed"
create_topic "price.updated"

# Payment topics
# Unified command topic — charge and cancel keyed by orderId → same partition.
create_topic "payment.cmd"
create_topic "payment.succeeded"
create_topic "payment.refunded"
create_topic "payment.failed"
create_topic "payment.dlq" 1   # DLQ single partition for ordering
# Legacy split topic kept for broker compatibility.
create_topic "payment.charge.cmd"

# Saga
create_topic "saga.compensate"

# Reservation
create_topic "reservation.promoted"

# Notification
create_topic "notification.send"

echo "All topics created successfully"
kafka-topics --bootstrap-server $KAFKA --list
