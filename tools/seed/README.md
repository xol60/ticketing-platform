# Overture realistic data seeder

Snapshot-hybrid seeder: **real** event catalog (or synthetic fallback) + a
**synthetic transactional layer**, so the admin / event-owner UI has rich,
realistic content (many events, users across all 4 roles, orders, payments,
confirmed tickets).

## Design

| Layer | How | Why |
|-------|-----|-----|
| Users + roles | REST register + SQL role promote | register hardcodes USER; promote via SQL |
| Events, tickets, price rules | **REST write path** | keeps outbox→Kafka→Elasticsearch consistent — events auto-index into search |
| Orders, payments, ticket confirmations | **bulk SQL** | thousands of live sagas would be far too slow; SQL writes consistent terminal states with `created_at` spread over ~60 days |

Cross-service invariants are preserved: every `CONFIRMED` order has its ticket
flipped to `CONFIRMED` + `locked_by_order_id` and a matching `SUCCESS` payment
(the same invariant `tests/stress-test.sh` validates).

## Prerequisites

- Stack running (`docker compose up -d`), nginx on `:80`.
- Node 20+, `docker` CLI.
- **Gateway rate limit off** for bulk registration:
  `GATEWAY_RATE_LIMIT_ENABLED=false` (already set in `docker-compose.yml`).
- Event ownership distribution needs RBAC Phase B's `events.owner_id` column
  (migration `V7`). Present already if the ticket-service is up to date.

## 1. (Optional) Fetch a real catalog snapshot

Without this step the seeder generates a synthetic-but-realistic catalog.

```bash
# Real data — free Ticketmaster Discovery key (https://developer.ticketmaster.com)
TM_API_KEY=your_key node tools/seed/fetch-snapshot.mjs --count=200

# or SeatGeek
SEATGEEK_CLIENT_ID=your_id node tools/seed/fetch-snapshot.mjs --source=seatgeek --count=200

# or force offline synthetic
node tools/seed/fetch-snapshot.mjs --synthetic --count=200
```

Writes `tools/seed/data/catalog-snapshot.json`. Committing it makes the seed
run fully offline and reproducible.

## 2. Seed

```bash
node tools/seed/seed.mjs --scale=medium
```

Scales (`--scale=`):

| scale  | users | owners | support | events | orders |
|--------|-------|--------|---------|--------|--------|
| smoke  | 6     | 2      | 1       | 3      | 15     |
| small  | 15    | 3      | 2       | 10     | 60     |
| medium | 50    | 6      | 3       | 40     | 300    |
| large  | 500   | 6      | 3       | 150    | 3000   |

Notes:
- Idempotent for users (re-register returns 409, skipped). **Events are always
  created fresh** — re-running adds more events. To reset, drop/recreate the
  stack volumes.
- All demo users share password `Test1234!`. Owners are `user0001..`, support
  next, rest are USER. Admin stays `admin` / `Admin@123456`.
- `SEED_BASE_URL` overrides the target (default `http://localhost`).

## Verify

The seeder prints per-table counts at the end. Spot-check cross-DB consistency:

```bash
# a seeded CONFIRMED order → its ticket should be CONFIRMED + locked to it
docker exec postgres-master psql -U ticketing -d order_db -tA -c \
  "SELECT id,ticket_id FROM orders WHERE status='CONFIRMED' AND payment_reference LIKE 'seed_%' LIMIT 1"
```

Search indexing: events created via REST auto-index; confirm with
`docker exec elasticsearch curl -s localhost:9200/events/_count`.
