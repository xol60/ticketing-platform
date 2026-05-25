# Performance, Scaling & Consumer Concurrency

How the platform handles load, scales out, and behaves when a consumer thread
stalls. Cross-cutting because it touches every service that consumes Kafka.

---

## Throughput at a single-replica baseline

| Layer                          | Capacity                                                      | Limited by                                |
| ------------------------------ | ------------------------------------------------------------- | ----------------------------------------- |
| Order API ingress              | ~2,000 req/s                                                  | Nginx `limit_req` (200r/s/IP × keepalive) |
| Kafka consumer (per listener)  | ~600 msg/s @ concurrency=3; ~2,000 msg/s on `ticket.cmd` @ concurrency=10 | threads × ~5 ms DB tx        |
| End-to-end saga (10 hops)      | < 1 s p50, < 2 s p99                                          | Race-test measured                        |
| Payment retry watchdog         | 50 payments / 2 s tick                                        | `BATCH_SIZE=50`, `fixedDelay=2 s`         |
| SSE connections per pod        | ~10,000                                                       | Nginx `worker_connections × cores`        |

---

## Where the bottleneck sits

```
Redis read       <  1 ms
Kafka roundtrip  1–2 ms
DB transaction   5–15 ms     ← actual bottleneck
Payment gateway  50–200 ms   ← OFFLOADED to watchdog, off consumer thread
```

Originally bottlenecked at the payment gateway because `gateway.charge() +
Thread.sleep()` ran on the Kafka consumer thread, freezing the partition.
Refactored to a claim-lease pattern in
[`PaymentRetryWatchdog`](../payment-service/src/main/java/com/ticketing/payment/watchdog/PaymentRetryWatchdog.java) —
consumer throughput jumped from ~15 → ~300 msg/s per partition.

---

## Horizontal scaling — pure ops, no code change

Every service is stateless at the JVM level (state lives in Postgres / Redis /
Kafka), so adding replicas is a Kafka rebalance:

| Action         | How                                                                 | Effect                                |
| -------------- | ------------------------------------------------------------------- | ------------------------------------- |
| Scale-out (HA) | `docker compose up -d --scale ticket-service=3`                     | Same throughput, 2-pod loss tolerance |
| Scale-up (TPS) | Bump partitions in `create-topics.sh` (idempotent), then scale pods | Linear up to partition count          |
| More DB reads  | Add a second `postgres-slave` + update routing                      | 2× read QPS                           |
| More Redis ops | Switch to Redis cluster (3 master + 3 replica)                      | 100k → 1M ops/s                       |

**Hard constraint:** `partitions ≥ total_consumer_threads` per service. The
`ensure_partitions` helper in `create-topics.sh` is idempotent so bumps
don't require redeploy.

---

## Why partitions = 10 even when consumers run with `concurrency=3`

Kafka allows a single consumer thread to read from multiple partitions, so 3
threads on a 10-partition topic isn't waste — each thread serves 3-4
partitions, round-robined. Pre-provisioning 10 gives us:

1. **A scaling ceiling.** Adding pods later increases consumer threads up to
   the partition count without re-sharding the topic.
2. **No need to re-key.** Raising the partition count later would change
   which partition each `orderId` hashes to, breaking per-order ordering
   during the transition. Picking 10 up front avoids ever doing that.
3. **Uniformity.** ticket.cmd's hot consumer actually uses all 10; we
   apply the same count to every saga-flow topic so partition counts
   don't vary per topic.

Two topics intentionally stay at 1 partition: `payment.dlq` (chronological
DLQ replay) and `auth.security.alert` (global ordering for forensics).

---

## Consumer concurrency and stalls — the slow-lane design

A common question: with `concurrency=3` on the typical listener, doesn't a
single stuck thread (Postgres connection wait, GC pause, network hang) drop
that listener to 66 % throughput? Yes — and that's the intended shape of
the failure mode, not a defect.

### Concurrency is per `@KafkaListener`, not per service

Each annotated listener gets its own container; the container factory's
concurrency setting applies per container. So saga-orchestrator's 11
listeners × 3 threads = 33 total consumer threads, but each individual
listener is still a 3-thread pool. A thread stuck inside the
`TICKET_RESERVED` listener costs 1/3 of *that listener's* throughput; the
adjacent `PAYMENT_SUCCEEDED` listener (different container, different
threads) keeps going at full rate.

### Why one listener (`ticket.cmd`) bumps to 10 and everything else stays at 3

`ticket.cmd` is the hot path: every order goes through it three times
(Reserve / Confirm / Release), it's the most contended topic, and a stall
there visibly slows checkout. We give it one thread per partition (10 of
10) so each partition has dedicated capacity. Every other listener handles
lower per-listener volume; 3 threads each owning ~3-4 partitions of a
10-partition topic is enough headroom, and over-provisioning consumer
threads wastes memory and connection-pool slots.

### What "stuck" actually does

When one thread of a 3-thread listener stalls:

| Effect | What happens |
| ------ | ------------ |
| The ~3-4 partitions assigned to that thread | Stop being processed entirely |
| The other ~6-7 partitions on the other threads of the same listener | Keep flowing at full rate |
| That listener's throughput | Drops to roughly 2/3 of nominal |
| Consumer lag | Builds monotonically on the stuck partitions of that one topic |
| Other `@KafkaListener`s on the same service | Unaffected — separate containers, separate threads |
| Other services / the user-facing POST | Unaffected |

It's a deterministic, scoped degradation — a **slow lane**, not a full
outage. The user-facing `POST /api/orders` doesn't move because the saga is
async under it; the symptom surfaces as "my order is PENDING for longer
than usual" on the tracker page.

### Why a stuck thread can't cascade into a full outage

Five properties combine so the 66 % drop is the worst case, not a death-spiral
precursor:

1. **Tight transactional scope.** Handlers use `TransactionTemplate` around
   the actual UPDATE, not `@Transactional` on the whole method. Connections
   are checked out for ~5 ms per message, so the consumer pool can't
   exhaust the connection pool from contention.
2. **Non-blocking concurrency primitives.** SETNX + `@Version` instead of
   `SELECT … FOR UPDATE`. Application-level hot-row contention cannot park
   a thread — only genuine infrastructure trouble can.
3. **`next_retry_at` for slow downstreams.** Payment retries don't sit on
   the consumer thread waiting for backoff. The handler stores
   `status=PENDING_RETRY, next_retry_at=now+backoff`, acks the message, and
   a `@Scheduled` job picks the retry up on its own threadpool.
4. **DLQ on retry exhaustion.** When payment retries run out, the message
   moves to `payment.dlq` for human inspection. The consumer doesn't loop
   forever on a permanently broken message.
5. **Watchdogs as the time bound.** Even if a saga genuinely wedges, the
   stuck-reservation watchdog releases the ticket within 60-120 s, so the
   stall is bounded in *time* rather than just *space*.

### When you'd diverge from this design

Three signals would push toward something different:

| Signal | What to do |
| ------ | ---------- |
| Lag grows during normal load (not stalls) | Bump partition count + consumer concurrency; cheapest scale knob |
| Handler genuinely needs to wait on slow I/O | Reactive consumer (`reactor-kafka`) so one thread handles many in-flight messages; cost is reactive-everywhere |
| Single hot key creates partition skew | Add a sharding suffix to the key; cost is losing strict per-original-key ordering |

Nothing in the current load profile triggers any of those. The 66 % drop on
a stalled thread is acceptable because it's bounded, visible (lag metric
spikes on one partition), and recoverable — the slow-lane shape we
designed for.

---

## Cross-instance synchronization

Two pods consuming different partitions can race on the same `orderId` or
the same DB row. Every such race closes at a **durable boundary** — DB,
Kafka, or Redis. **All mechanisms are non-blocking** — no
`SELECT … FOR UPDATE`, no app-level `synchronized` blocks. Both would tie
up Kafka consumer threads, and per-listener thread counts are sized tight
(3 per listener everywhere, 10 on the one hot `ticket.cmd` listener), so a
parked thread is a visible loss of capacity on that listener's topic.

| Mechanism                             | Closes which race                                       |
| ------------------------------------- | ------------------------------------------------------- |
| Redis `SETNX` distributed lock        | Two pods racing to reserve the same ticket; two pods promoting the same waitlist head |
| Optimistic `@Version` on entities     | Belt-and-suspenders: catches concurrent UPDATE if Redis fails or its TTL expires mid-flight |
| UNIQUE partial index                  | DB-level overselling / double-listing                   |
| Kafka consumer-group assignment       | Two pods consuming the same partition (broker enforced) |
| `orderId` partition key               | `Release` overtaking `Reserve` across pods              |
| Claim-lease (`nextRetryAt` window)    | Two payment pods double-charging the same payment       |
| Write-through saga state (PG → Redis) | Lost progress on Redis flush or pod restart             |
| Idempotency (sagaId / payment state)  | Duplicate processing on Kafka redelivery                |
