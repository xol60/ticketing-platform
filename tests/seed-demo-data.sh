#!/bin/bash
# ============================================================================
#  Demo-data seeder — Postgres + Elasticsearch in one shot
# ============================================================================
#
#  PROBLEM
#    `tests/seed-demo-data.sql` does direct INSERTs into Postgres, which is
#    fast but bypasses Kafka. Search-service never sees these events, so
#    /api/search/events returns nothing for them until something triggers a
#    re-publish on `event.search.indexed`.
#
#  WHAT THIS SCRIPT DOES
#    1.  Runs the SQL seed on auth_db (demouser) and ticket_db (events + tickets).
#    2.  For every event the SQL marked as OPEN, calls
#          PATCH /api/tickets/events/{eventId}/open
#        which is a no-op state transition that nevertheless triggers
#        EventService.publishSearchIndexed(...) → search-service consumes it
#        → ES upserts the doc. Within seconds, /api/search/events is hot.
#    3.  Polls Elasticsearch's /_count endpoint until the index reflects the
#        new docs, so the caller has a usable demo data set on return.
#
#  USAGE
#    bash tests/seed-demo-data.sh
#
#  PREREQUISITES
#    docker compose up -d   # the platform must be running
#    Admin account must exist with password "Admin@123456" (default seed).
#
#  IDEMPOTENT
#    Both the SQL and the PATCH calls are safe to re-run. SQL uses
#    ON CONFLICT DO NOTHING; PATCH /open on an already-OPEN event is a no-op
#    but still re-publishes the indexing event.
# ============================================================================

set -eo pipefail

NGINX_URL="${NGINX_URL:-http://localhost}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-Admin@123456}"
SQL_FILE="$(cd "$(dirname "$0")" && pwd)/seed-demo-data.sql"

if [ ! -f "$SQL_FILE" ]; then
  echo "❌ Cannot find $SQL_FILE"
  exit 1
fi

echo "🗄  Running SQL seed against auth_db + ticket_db..."
docker exec -i postgres-master psql -U ticketing -d auth_db   < "$SQL_FILE" 2>&1 | grep -E 'NOTICE|ERROR' || true
docker exec -i postgres-master psql -U ticketing -d ticket_db < "$SQL_FILE" 2>&1 | grep -E 'NOTICE|ERROR' || true

# ── 1. Login as admin to get a token for the PATCH calls ─────────────────────
echo "🔑 Logging in as $ADMIN_USER..."
LOGIN=$(curl -sf -X POST "$NGINX_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"emailOrUsername\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.accessToken')
USER_ID=$(echo "$LOGIN" | jq -r '.data.userId')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "❌ Admin login failed — check ADMIN_USER / ADMIN_PASS env vars"
  exit 1
fi

# ── 2. Trigger a no-op re-publish on every seeded OPEN event ─────────────────
EVENT_IDS=$(docker exec postgres-master psql -U ticketing -d ticket_db -tAc \
  "SELECT id FROM events WHERE id LIKE 'demo-evt-%' AND status='OPEN' ORDER BY id")

OPEN_COUNT=0
for EVT in $EVENT_IDS; do
  OPEN_COUNT=$((OPEN_COUNT + 1))
  # `--max-time 10` so a hung connection doesn't stall the whole loop;
  # `|| true` because some curl builds report exit 18 on a clean close (the
  # response actually arrived but the connection was reset mid-keepalive).
  curl -s --max-time 10 -X PATCH "$NGINX_URL/api/tickets/events/$EVT/open" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-User-Id: $USER_ID" \
    -H "Content-Type: application/json" \
    -o /dev/null || true
done
echo "📡 Triggered re-publish on $OPEN_COUNT OPEN events (event.search.indexed)"

# ── 3. Wait for Elasticsearch to catch up ────────────────────────────────────
# Demo data uses ids prefixed with "demo-evt-" — count those specifically so
# we don't get confused by any other events already in the index.
echo -n "⏳ Waiting for Elasticsearch to index the demo events"
for attempt in $(seq 1 30); do
  # ES stores the document key in the special `_id` meta-field, not in a
  # queryable `id` field — so we count docs whose name starts with one of our
  # known seed prefixes ("Coldplay", "Taylor Swift", etc.). The match_phrase_prefix
  # against name.edge (edge_ngram analyzer) reliably picks them up.
  ES_COUNT=$(docker exec elasticsearch curl -s \
    -H 'Content-Type: application/json' \
    "http://localhost:9200/events/_count" \
    -d '{"query":{"ids":{"values":["demo-evt-01-0000-0000-000000000001","demo-evt-02-0000-0000-000000000002","demo-evt-03-0000-0000-000000000003","demo-evt-04-0000-0000-000000000004","demo-evt-05-0000-0000-000000000005","demo-evt-06-0000-0000-000000000006","demo-evt-07-0000-0000-000000000007","demo-evt-08-0000-0000-000000000008","demo-evt-09-0000-0000-000000000009","demo-evt-10-0000-0000-000000000010"]}}}' \
    | jq -r '.count // 0')
  if [ "$ES_COUNT" = "$OPEN_COUNT" ]; then
    echo
    echo "✅ Elasticsearch reports $ES_COUNT demo events indexed"
    break
  fi
  echo -n "."
  sleep 1
done

# ── Summary ──────────────────────────────────────────────────────────────────
echo
echo "═══ Seed complete ═══"
echo "  Demo user      : demouser / Test1234!  (USER role)"
echo "  Events seeded  : $(docker exec postgres-master psql -U ticketing -d ticket_db -tAc "SELECT COUNT(*) FROM events WHERE id LIKE 'demo-evt-%'") total"
echo "                   ↳ $(docker exec postgres-master psql -U ticketing -d ticket_db -tAc "SELECT COUNT(*) FROM events WHERE id LIKE 'demo-evt-%' AND status='OPEN'") OPEN"
echo "                   ↳ $(docker exec postgres-master psql -U ticketing -d ticket_db -tAc "SELECT COUNT(*) FROM events WHERE id LIKE 'demo-evt-%' AND status NOT IN ('OPEN')") non-OPEN (negative cases)"
echo "  Tickets seeded : $(docker exec postgres-master psql -U ticketing -d ticket_db -tAc "SELECT COUNT(*) FROM tickets WHERE id LIKE 'demo-tkt-%' AND status='AVAILABLE'") AVAILABLE"
echo "  ES indexed     : $ES_COUNT / $OPEN_COUNT demo events"
echo
echo "  Try it:"
echo "    curl '$NGINX_URL/api/search/events?q=coldplay' | jq"
echo "    curl '$NGINX_URL/api/search/events/suggest?q=co' | jq"
echo "    open $NGINX_URL  (UI — search box top-right of the events page)"
