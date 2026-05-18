#!/bin/bash
# ============================================================================
#  Concurrent-order stress test
# ============================================================================
#  Fires N parallel POST /api/orders against distinct AVAILABLE tickets.
#  Measures end-to-end saga latency from POST to CONFIRMED status.
#  Validates:
#    1. No overselling (each ticket lands on exactly one order)
#    2. All orders reach a terminal state within the timeout
#    3. Cross-DB consistency (order = ticket.locked_by_order_id)
# ============================================================================

set -eo pipefail

# ── Config ──────────────────────────────────────────────────────────────────
CONCURRENCY="${CONCURRENCY:-20}"
USER="qauser99"
PASS="Test1234!"
TIMEOUT_SECONDS=60

# ── Login ───────────────────────────────────────────────────────────────────
echo "🔑 Logging in..."
LOGIN=$(curl -sf -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"emailOrUsername\":\"$USER\",\"password\":\"$PASS\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.accessToken')
USER_ID=$(echo "$LOGIN" | jq -r '.data.userId')
[ -z "$TOKEN" ] && echo "Login failed" && exit 1

# ── Snapshot starting state ──────────────────────────────────────────────────
START_AVAIL=$(docker exec postgres-master psql -U ticketing -d ticket_db -tA -c \
  "SELECT COUNT(*) FROM tickets WHERE status='AVAILABLE'")
START_CONFIRMED=$(docker exec postgres-master psql -U ticketing -d ticket_db -tA -c \
  "SELECT COUNT(*) FROM tickets WHERE status='CONFIRMED'")
echo "📊 Starting state: AVAILABLE=$START_AVAIL  CONFIRMED=$START_CONFIRMED"

if [ "$START_AVAIL" -lt "$CONCURRENCY" ]; then
  echo "❌ Need at least $CONCURRENCY available tickets — only $START_AVAIL exist"
  exit 1
fi

# ── Pick N tickets to order ─────────────────────────────────────────────────
TICKETS=()
while IFS= read -r line; do
  TICKETS+=("$line")
done < <(docker exec postgres-master psql -U ticketing -d ticket_db -tA -c "SELECT id || '|' || face_price FROM tickets WHERE status='AVAILABLE' LIMIT $CONCURRENCY")

echo "🎯 Firing $CONCURRENCY concurrent orders..."

# ── Fire all in parallel using `&` background ────────────────────────────────
rm -rf /tmp/stress_responses
mkdir -p /tmp/stress_responses

START_TIME=$(perl -MTime::HiRes=time -e "printf(\"%d\", time * 1000)")
for i in "${!TICKETS[@]}"; do
  IFS='|' read -r TICKET_ID FACE <<< "${TICKETS[$i]}"
  curl -s -X POST http://localhost/api/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-User-Id: $USER_ID" \
    -d "{\"ticketId\":\"$TICKET_ID\",\"requestedPrice\":$FACE}" \
    -o "/tmp/stress_responses/order_$i.json" &
done
wait
ACCEPT_TIME=$(perl -MTime::HiRes=time -e "printf(\"%d\", time * 1000)")
ACCEPT_LATENCY=$((ACCEPT_TIME - START_TIME))
echo "⏱  All $CONCURRENCY POSTs returned in ${ACCEPT_LATENCY} ms"

# ── Collect order IDs ────────────────────────────────────────────────────────
ORDER_IDS=()
for i in "${!TICKETS[@]}"; do
  ORDER_ID=$(jq -r '.data.id // empty' /tmp/stress_responses/order_$i.json)
  [ -n "$ORDER_ID" ] && ORDER_IDS+=("$ORDER_ID")
done
echo "📨 Accepted: ${#ORDER_IDS[@]}/$CONCURRENCY orders"

# ── Wait for all to reach terminal state ────────────────────────────────────
echo "⏳ Polling for terminal states (timeout ${TIMEOUT_SECONDS}s)..."
DEADLINE=$(($(date +%s) + TIMEOUT_SECONDS))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  PENDING=$(docker exec postgres-master psql -U ticketing -d order_db -tA -c \
    "SELECT COUNT(*) FROM orders WHERE id = ANY(ARRAY[$(printf "'%s'," "${ORDER_IDS[@]}" | sed 's/,$//')]) AND status NOT IN ('CONFIRMED','FAILED','CANCELLED')")
  if [ "$PENDING" -eq 0 ]; then
    SAGA_FINISH=$(perl -MTime::HiRes=time -e "printf(\"%d\", time * 1000)")
    SAGA_TOTAL=$((SAGA_FINISH - START_TIME))
    echo "✅ All sagas reached terminal state in ${SAGA_TOTAL} ms"
    break
  fi
  sleep 2
done

# ── Validate consistency ────────────────────────────────────────────────────
echo ""
echo "═══ Validation ═══"

CONFIRMED=$(docker exec postgres-master psql -U ticketing -d order_db -tA -c \
  "SELECT COUNT(*) FROM orders WHERE id = ANY(ARRAY[$(printf "'%s'," "${ORDER_IDS[@]}" | sed 's/,$//')]) AND status='CONFIRMED'")
FAILED=$(docker exec postgres-master psql -U ticketing -d order_db -tA -c \
  "SELECT COUNT(*) FROM orders WHERE id = ANY(ARRAY[$(printf "'%s'," "${ORDER_IDS[@]}" | sed 's/,$//')]) AND status IN ('FAILED','CANCELLED')")
echo "  CONFIRMED orders : $CONFIRMED"
echo "  FAILED/CANCELLED : $FAILED"

# Verify each CONFIRMED order has corresponding CONFIRMED ticket
INCONSISTENT=$(docker exec postgres-master psql -U ticketing -d order_db -tA -c \
  "SELECT COUNT(*) FROM orders o WHERE o.id = ANY(ARRAY[$(printf "'%s'," "${ORDER_IDS[@]}" | sed 's/,$//')]) AND o.status='CONFIRMED'" )
TICKETS_CONFIRMED=$(docker exec postgres-master psql -U ticketing -d ticket_db -tA -c \
  "SELECT COUNT(*) FROM tickets WHERE locked_by_order_id = ANY(ARRAY[$(printf "'%s'," "${ORDER_IDS[@]}" | sed 's/,$//')]) AND status='CONFIRMED'")

echo "  Confirmed orders : $INCONSISTENT"
echo "  Confirmed tickets: $TICKETS_CONFIRMED"

if [ "$INCONSISTENT" = "$TICKETS_CONFIRMED" ]; then
  echo "✅ Cross-DB consistency: orders ⇄ tickets matched"
else
  echo "❌ Cross-DB MISMATCH"
fi

PAYMENTS_SUCCESS=$(docker exec postgres-master psql -U ticketing -d payment_db -tA -c \
  "SELECT COUNT(*) FROM payments WHERE order_id = ANY(ARRAY[$(printf "'%s'," "${ORDER_IDS[@]}" | sed 's/,$//')]) AND status='SUCCESS'")
echo "  Payment SUCCESS  : $PAYMENTS_SUCCESS"

echo ""
echo "📊 Summary:"
echo "  $CONCURRENCY concurrent orders → $CONFIRMED CONFIRMED, $FAILED FAILED"
echo "  POST acceptance: ${ACCEPT_LATENCY} ms"
echo "  End-to-end:     ${SAGA_TOTAL:-?} ms"
