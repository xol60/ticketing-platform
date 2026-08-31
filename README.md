# Overture — Event Ticketing Platform

Microservice ticketing system — Java 21 + Spring Boot 3.2 + Kafka + Redis + Postgres,
with a **conversational recommendation agent running entirely on local models**.

Two things here are worth more attention than the service count:

- **A saga that survives partial failure.** Reserve → price lock → payment → confirm,
  with compensation, idempotency at every hop, and no service-to-service HTTP in any
  business workflow.
- **An agent built on the assumption that its model lies.** Inference runs on
  `qwen3:8b` in Ollama — no API key, no data leaving the machine — and an 8B model
  fabricates readily and *fluently*. Every fact it produces has to quote the sentence
  it came from, and is thrown away if that sentence is not there.

▶︎ **[Recommendation agent](#recommendation-agent)** — flow, models, hard limits, and
what the measurements actually say.

## Prerequisites

| Tool           | Version |
| -------------- | ------- |
| Java           | 21+     |
| Maven          | 3.9+    |
| Docker         | 24+     |
| Docker Compose | 2.24+   |

## System architecture

Four-tier topology. Browser → nginx (edge) → API gateway (auth + circuit-breaker + rate-limit) →
eleven Spring Boot services that communicate **only via Kafka** (no service-to-service HTTP for
business workflows) → shared Postgres + Redis + Elasticsearch storage, plus a local Ollama
runtime used by the recommendation agent.

```mermaid
flowchart LR
    Browser[Browser] --> Nginx
    Nginx --> Gateway[API Gateway]
    Gateway --> Services
    Services <--> Kafka[(Kafka)]
    Services --> PgMaster[(Postgres master)]
    Services -.reads.-> PgSlave[(Postgres slave)]
    Services <--> Redis[(Redis)]
    S10 --> ES[(Elasticsearch)]
    S11 --> PgVec[(agent_db + pgvector)]
    S11 <--> Ollama[[Ollama - local models]]
    PgMaster -.replication.-> PgSlave

    subgraph Services[" 11 Spring Boot services "]
        S1[Auth]
        S2[Order]
        S3[Ticket]
        S4[Pricing]
        S5[Payment]
        S6[Saga]
        S7[Reservation]
        S8[SecMarket]
        S9[Notification]
        S10[Search]
        S11[Agent]
    end
```

Two services sit outside the order saga, both as read models off the same
`event.search.indexed` topic and neither aware of the other:

- **search-service** projects events into a read-only Elasticsearch index for
  keyword search.
- **agent-service** projects them into Postgres + pgvector for the conversational
  recommendation agent.

Postgres remains the source of truth for both. If either is down — or Ollama is
not running — the rest of the platform keeps selling tickets.

> ▶️ **[Animated saga flows](https://htmlpreview.github.io/?https://github.com/xol60/ticketing-platform/blob/main/docs/animated-flows.html)** — interactive play/pause/step demo of all three scenarios.
> 📄 **[Static reference](https://htmlpreview.github.io/?https://github.com/xol60/ticketing-platform/blob/main/docs/diagrams.html)** — scroll-through version for readers who prefer text + step lists.

Key properties:

- **No HTTP between services for business workflows** → a downed service degrades into consumer
  lag, not cascading 5xx.
- **9 logical Postgres DBs on one master** → bounded-context isolation without paying for 9 clusters.
- **Redis is acceleration, not source of truth** → saga state writes Postgres first; Redis outage
  causes slow reads, not data loss.

## Project structure

Each service has its own README with API, internal architecture, and operational notes.

| Path | Headline |
| ---- | -------- |
| [`common-lib/`](common-lib)                                       | Shared events, DTOs, exceptions, the Stripe-pattern idempotency filter |
| [`api-gateway/`](api-gateway)                                     | Reactive gateway — traceId, rate limiter, circuit breaker, auth cache  |
| [`auth-service/`](auth-service)                                   | JWT issue + refresh                                                    |
| [`ticket-service/`](ticket-service)                               | Aggregate root, inventory lock, hot consumer at `concurrency=10`       |
| [`order-service/`](order-service)                                 | Workflow orchestrator + HTTP idempotency on `POST /api/orders`         |
| [`saga-orchestrator/`](saga-orchestrator)                         | Distributed transaction middleware, 11 saga listeners                  |
| [`pricing-service/`](pricing-service)                             | Dynamic surge pricing with point-in-time validation                    |
| [`reservation-service/`](reservation-service)                     | Waitlist queue                                                         |
| [`payment-service/`](payment-service)                             | External payment + DLQ + admin alert + claim-lease retry watchdog      |
| [`secondary-market-service/`](secondary-market-service)           | Ticket resale + HTTP idempotency on `POST /api/secondary/listings`     |
| [`notification-service/`](notification-service)                   | Email / push                                                           |
| [`search-service/`](search-service)                               | Elasticsearch read-only event search — multi-field + autocomplete + fuzzy |
| [`agent-service/`](agent-service)                                 | Conversational recommendation agent — local LLM, pgvector, six validation gates |
| [`ticketing-ui/`](ticketing-ui)                                   | React + TypeScript SPA                                                 |
| [`tests/`](tests)                                                 | Concurrent-order stress test, demo-data seeder, hand-labelled agent retrieval eval |
| [`docs/`](docs)                                                   | Cross-cutting deep dives (see [Deep dives](#deep-dives) below)         |
| `docker/`                                                         | Topic-creation script, Postgres master+slave config, Redis & Nginx confs |

## Quick start

### 1. Clone and configure

```bash
cp .env.example .env
# Generate a real JWT secret
openssl rand -base64 32
# Paste the output into .env as JWT_SECRET
```

### 2. Build all services

```bash
mvn clean package -DskipTests
```

### 3. Run (development)

Exposes all service ports locally and enables DEBUG logging + JVM remote debug on each service:

```bash
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

Remote debug ports: `508{1-9}` — e.g. auth-service → `5081`, ticket-service → `5082`.

### 4. Run (production)

Adds `restart: always`, memory/CPU limits, and hides all ports except Nginx:80:

```bash
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### 5. Run (bare / default)

```bash
docker-compose up --build
```

### 6. Verify health

```bash
# Gateway (via nginx)
curl http://localhost/actuator/health

# Direct service ports (dev mode only)
curl http://localhost:8082/actuator/health   # ticket-service
curl http://localhost:8083/actuator/health   # order-service
```

## Development workflow

### Run a single service locally against Docker infra

```bash
# Start infra only
docker-compose up postgres-master redis kafka kafka-init -d

# Run any service with the 'local' profile (uses localhost ports)
cd ticket-service
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Rebuild one service without restarting everything

```bash
docker-compose up --build --no-deps ticket-service
```

## Service ports (internal Docker network)

| Service              | Port                           |
| -------------------- | ------------------------------ |
| Nginx (public)       | 80                             |
| API Gateway          | 8090                           |
| Auth Service         | 8081                           |
| Ticket Service       | 8082                           |
| Order Service        | 8083                           |
| Saga Orchestrator    | 8084                           |
| Pricing Service      | 8085                           |
| Reservation Service  | 8086                           |
| Payment Service      | 8087                           |
| Secondary Market     | 8088                           |
| Notification Service | 8089                           |
| Search Service       | 8091                           |
| Agent Service        | 8092                           |
| Postgres Master      | 5432 (internal) / 5436 (host)  |
| Redis                | 6379                           |
| Kafka                | 9092 (internal) / 29092 (host) |
| Elasticsearch        | 9200 (internal)                |
| Ollama               | 11434 (host, not containerised on macOS) |

## Kafka topics

27 topics in total. Saga-flow topics use **10 partitions** by default, with the
partition key chosen so messages that need ordering land on the same partition
(usually `orderId`, sometimes `eventId` or `ticketId`). Two topics keep
**1 partition** intentionally (`payment.dlq`, `auth.security.alert`) so DLQ
replay and security forensics get strict global ordering.

**Consumer concurrency is set per `@KafkaListener`, not per service.** One
listener bumps to 10 threads because it's the single hottest consumer in
the system; everything else stays at the default of 3 threads per listener:

| Listener | Concurrency | Partitions each thread owns |
| -------- | ----------- | --------------------------- |
| `ticket-service` on `ticket.cmd` (the hot consumer) | **10** | Exactly 1 — one thread per partition for maximum parallelism on the saga's hot path |
| Every other `@KafkaListener` in the system | **3** | ~3-4 each — handles lower per-listener volume on a 10-partition topic |

Total consumer thread counts per service therefore depend on how many
listeners each service has (saga-orchestrator alone hosts 11 listeners ×
3 threads = 33 total). The *single* place we depart from "3 per listener"
is the `ticket.cmd` consumer.

See [Consumer concurrency and stalls — the slow-lane design](#consumer-concurrency-and-stalls--the-slow-lane-design) below for why this one exception, and what happens when a thread stalls.

### Unified command topics — the key ordering decision

`ticket-service` and `payment-service` each accept **multiple command types on one
topic** instead of one topic per command:

| Service         | Unified topic | Carries                                                                |
| --------------- | ------------- | ---------------------------------------------------------------------- |
| ticket-service  | `ticket.cmd`  | `TicketReserveCommand`, `TicketConfirmCommand`, `TicketReleaseCommand` |
| payment-service | `payment.cmd` | `PaymentChargeCommand`, `PaymentCancelCommand`                         |

If `Release` and `Confirm` lived on separate topics, two consumer threads could pick
them up concurrently — `Release` winning the race would emit a spurious
`TicketReservationFailed`. The payment analogue is worse: a `Cancel` processed before
its preceding `Charge` would silently drop, leaving the customer charged. Unified
topics + `orderId` partition key guarantee strict per-order ordering without any
application-level locking.

### Catalog (consolidated)

| Domain      | Topic                   | Producers               | Consumers (`@KafkaListener`) | Carries (event/command DTOs)                   | P     | C      | Key      |
| ----------- | ----------------------- | ----------------------- | ---------------------------- | ---------------------------------------------- | ----- | ------ | -------- |
| Order       | `order.created`         | order, secondary-market | saga                         | `OrderCreatedEvent`                            | 10    | 3      | orderId  |
| Order       | `order.confirmed`       | saga                    | order, reservation           | `OrderConfirmedEvent`                          | 10    | 3      | orderId  |
| Order       | `order.failed`          | saga                    | order                        | `OrderFailedEvent`                             | 10    | 3      | orderId  |
| Order       | `order.cancelled`       | saga                    | order                        | `OrderCancelledEvent`                          | 10    | 3      | orderId  |
| Order       | `order.price.changed`   | saga                    | order                        | `OrderPriceChangedEvent`                       | 10    | 3      | orderId  |
| Order       | `order.price.confirm`   | order                   | saga                         | `OrderPriceConfirmCommand`                     | 10    | 3      | orderId  |
| Order       | `order.price.cancel`    | order                   | saga                         | `OrderPriceCancelCommand`                      | 10    | 3      | orderId  |
| Ticket      | **`ticket.cmd`**        | saga                    | ticket                       | `Reserve` / `Confirm` / `Release` Command      | 10    | **10** | orderId  |
| Ticket      | `ticket.reserved`       | ticket                  | saga, pricing                | `TicketReservedEvent`                          | 10    | 3      | orderId  |
| Ticket      | `ticket.released`       | ticket                  | saga, reservation, pricing   | `TicketReleasedEvent`                          | 10    | 3      | orderId  |
| Ticket      | `ticket.confirmed`      | ticket                  | saga, notification           | `TicketConfirmedEvent`                         | 10    | 3      | orderId  |
| Pricing     | `pricing.lock.cmd`      | saga                    | pricing                      | `PriceLockCommand`                             | 10    | 3      | orderId  |
| Pricing     | `pricing.locked`        | pricing                 | saga                         | `PricingLockedEvent`                           | 10    | 3      | orderId  |
| Pricing     | `pricing.price.changed` | pricing                 | saga                         | `PriceChangedEvent`                            | 10    | 3      | orderId  |
| Pricing     | `pricing.failed`        | pricing                 | saga                         | `PricingFailedEvent`                           | 10    | 3      | orderId  |
| Pricing     | `price.updated`         | pricing                 | _(SSE fan-out, no listener)_ | `PriceUpdatedEvent`                            | 10    | —      | eventId  |
| Payment     | **`payment.cmd`**       | saga                    | payment                      | `Charge` / `Cancel` Command                    | 10    | 3      | orderId  |
| Payment     | `payment.succeeded`     | payment                 | saga                         | `PaymentSucceededEvent`                        | 10    | 3      | orderId  |
| Payment     | `payment.failed`        | payment                 | saga                         | `PaymentFailedEvent`                           | 10    | 3      | orderId  |
| Payment     | `payment.refunded`      | payment                 | _(none — fan-out)_           | `PaymentRefundedEvent`                         | 10    | —      | orderId  |
| Payment     | `payment.dlq`           | payment                 | notification                 | `PaymentFailedEvent` (after retries exhausted) | **1** | 3      | orderId  |
| Reservation | `reservation.promoted`  | reservation             | _(none — fan-out)_           | `ReservationPromotedEvent`                     | 10    | —      | ticketId |
| Event mgmt  | `event.status.changed`  | ticket                  | _(none — fan-out)_           | `EventStatusChangedEvent`                      | 10    | —      | eventId  |
| Event mgmt  | `event.search.indexed`  | ticket                  | search, **agent**            | `EventSearchIndexedEvent`                      | 10    | 3      | eventId  |
| Event mgmt  | `event.hotness.changed` | ticket                  | order                        | `EventHotnessChangedEvent`                     | 10    | 3      | eventId  |
| Notif       | `notification.send`     | any service             | notification                 | `NotificationSendCommand`                      | 10    | 3      | orderId  |
| Security    | `auth.security.alert`   | auth                    | notification                 | `AuthSecurityAlertEvent`                       | **1** | 3      | userId   |

**P** = partitions. **C** = per-listener consumer concurrency. `payment.dlq`
and `auth.security.alert` keep 1 partition for strict global ordering
(chronological DLQ replay and security forensics). The C column shows the
deliberate exception: `ticket.cmd` is the one consumer bumped to 10 threads
(one per partition) because it's the hot path of every saga; everywhere else
the default 3 is enough. Topics with `—` are produced but have no
`@KafkaListener` consumer today (fan-out / future-subscribers / pulled via
SSE) — see notes below.

**Why some topics have no listener:**
- `price.updated`: pulled by clients via the SSE stream at `/api/pricing/stream/**`,
  not consumed by any service.
- `payment.refunded`: emitted as a notification-style fact; the saga has
  already advanced to compensation by the time refunds happen, and the
  refund itself is observable via `payment.dlq` if it fails.
- `reservation.promoted`: emitted as a fact; the waitlist promotion is
  visible through the order's own state transitions.
- `event.status.changed`: kept as a separate topic so future subscribers
  (analytics, audit, indexers) can opt in without ticket-service knowing
  about them. The search-service uses the related `event.search.indexed`
  topic instead.

## Order placement — fast-fail ingress + saga flows

`POST /api/orders` has two stages: a **fast-fail ingress** that rejects races
before any persistent state is touched, and the **saga** that runs after a
request actually claims a slot. The same three saga flows live below the ingress.

> ▶️ **[Animated saga flows](https://htmlpreview.github.io/?https://github.com/xol60/ticketing-platform/blob/main/docs/animated-flows.html)** — interactive play/pause/step demo.
> 📄 **[Static reference](https://htmlpreview.github.io/?https://github.com/xol60/ticketing-platform/blob/main/docs/diagrams.html)** — scroll-through HTML version.

### Stage 0 — fast-fail tiers in order-service

Under flash-sale conditions, hundreds of users click Buy on the same ticket
within milliseconds. Without ingress filters, every one would create an
Order row, fire an `OrderCreatedEvent`, instantiate saga state, and only
fail later at the saga's `SETNX` on `ticket:lock:{id}` — DB inserts and
Kafka traffic for every loser. Three tiers cut that to ~1:

```
POST /api/orders {ticketId, requestedPrice}
   │
   ├─► Tier 1 — local Caffeine "order-ticket-status" (per-pod, µs)
   │     populated by TicketStateConsumer subscribing to ticket.reserved
   │     / confirmed / released. cross-pod convergence at Kafka latency.
   │     hit → 409 immediately (no DB, no Kafka)
   │
   ├─► Tier 2 — Redis SETNX "order-intent:{ticketId}" (cross-pod, ~0.5ms)
   │     held for the few-ms window until the saga's authoritative lock
   │     takes over. 5s TTL safety; TicketStateConsumer also DELs on
   │     ticket.reserved to release sooner.
   │     held by another → 409
   │
   ├─► Existing guard checks (event open? user allowed to purchase?)
   │     run in parallel via guardCheckExecutor
   │
   └─► Order INSERT + OrderCreatedEvent → saga starts (next subsection)
       Tier 3 — the saga's own SETNX on ticket:lock:{ticketId} +
       JPA @Version is the authoritative gate in ticket-service.
```

Measured outcome (20 concurrent POSTs on same ticket):
**1 Order created, 19 rejected at Tier 2** in ~ms each — instead of 20
Order INSERTs plus 19 saga compensations.

### Stage 1 — saga flows

```mermaid
flowchart LR
    Start([Saga starts]) --> Reserve[Reserve ticket]
    Reserve --> Lock{Pricing lock}
    Lock -->|Case B: exact match| Pay[Charge payment]
    Lock -->|Case C: surge moved| Wait[AWAITING_PRICE_CONFIRMATION<br/>30s window]
    Wait -->|user confirms| Pay
    Wait -->|user cancels<br/>or watchdog timeout| Comp[Release ticket lock]
    Pay --> Confirm[Confirm ticket]
    Confirm --> Done([COMPLETED])
    Comp --> Cancel([CANCELLED])
```

| Flow                          | Divergence rule                                      | Saga      | Order     | Ticket    | Payment       |
| ----------------------------- | ---------------------------------------------------- | --------- | --------- | --------- | ------------- |
| **1 — Happy path**            | `userPrice == facePrice × multiplierAtOrderTime`     | COMPLETED | CONFIRMED | CONFIRMED | SUCCESS       |
| **2 — Accept new price**      | Case C → user POSTs `/confirm-price`                 | COMPLETED | CONFIRMED | CONFIRMED | SUCCESS (new) |
| **3 — Decline / 30s timeout** | Case C → `/cancel-price` **or** SagaWatchdog expires | CANCELLED | CANCELLED | AVAILABLE | (none)        |

All three flows converge to a **fully consistent terminal state across 4 databases** —
no orphan rows, no half-charged payments, no leaked ticket locks.

## Performance, scaling & operations

Baseline throughput, bottleneck analysis, horizontal scaling, cross-instance
synchronization, the deliberate consumer-concurrency story (one listener at
10, everything else at 3 — and what that means when a thread stalls):

▶︎ **[`docs/PERFORMANCE.md`](docs/PERFORMANCE.md)**

The headline: every service is stateless at the JVM level so scale-out is a
Kafka rebalance. A stuck consumer thread costs ~33 % of *one listener's*
throughput, not the whole service — the design contains stalls in a slow
lane rather than letting them cascade into outages.

## Recommendation agent

A conversational funnel over the catalogue: someone describes what they feel
like doing, the agent narrows it down over a few turns, and hands back an event
id. Runs on `agent-service` (port 8092) against locally-hosted models — the
stack needs no API key and no event data leaves the machine.

### The problem this is actually solving

Running inference locally is the point, and it is also the constraint. `qwen3:8b`
does not produce obvious nonsense — it produces **fluent, correctly-structured,
plausible facts about things the source never said**. An invented facet reads
exactly like a real one, sits in the right dimension, and snaps to a sensible
tag. Nothing about its shape gives it away.

So the design assumes the model lies, and makes that assumption cheap to act on:

> Every facet must quote the exact words it was derived from, and Java discards
> it if that quote does not appear verbatim in the source.

This is the strongest guard in the system precisely because it is dumb.
Fabricating a plausible sentence is easy for a small model; fabricating a
character sequence that happens to occur in one specific paragraph is not. It
costs a string comparison, needs no threshold, and runs before anything is
embedded.

On the demo catalogue it rejects **40% of what the model produces** — 29% of
those for citing text that does not exist. Those rejections are kept, not
dropped: their distribution across reasons is the only honest measure of how
much the model is inventing, and it is what tells you where the prompt is wrong.

### What it is not

The terminal action is a deep link to the existing event page. The agent **never
holds a ticket, creates an order, or takes a lock**, and it has no tool that
could. Everything transactional stays behind the checkout seam it hands off to.

That boundary is what makes the subsystem cheap: no reservation TTL to expire,
nothing to release when a conversation is abandoned, and no path by which a bug
here can strand a ticket. If an event sells out mid-conversation, checkout
reports it like it would for any other buyer.

There is also **no agent loop**. The action space is closed and small, so an
orchestrator picking its own next tool would buy nothing but non-determinism.
Control flow is ordinary Java; the model sits at fixed call sites and does one
narrow job at each.

### Models

| Role | Model | Why |
| --- | --- | --- |
| Extraction | `qwen3:8b` (~5.2 GB) | Best JSON-schema adherence in the 8B class. Ingestion must return exact structure, and 4B variants fabricate spans outright. |
| Embedding | `bge-m3` (1024-dim) | Width matches `vector(1024)` in the schema, so no migration. Multilingual, and needs no instruction prefix. |

Both served by Ollama. **On macOS Ollama runs natively, not in a container** —
Docker there is a VM with a fixed memory ceiling the rest of the stack largely
fills, and a container cannot reach the Apple Silicon GPU at all. The compose
file carries an `ollama-container` profile for Linux, where Docker shares host
memory directly:

```bash
# macOS
brew install ollama && ollama serve
ollama pull qwen3:8b && ollama pull bge-m3

# Linux
docker compose --profile ollama-container up -d
```

### Two flows

**Ingestion** (offline, once per event) consumes `event.search.indexed` — the
same topic search-service reads — and turns a description into facets:

```
event.search.indexed → extract (1 LLM call) → 4 deterministic gates
                     → embed (value + span) → dim gate
                     → nearest 3 tags on the facet's own dim → agent_db
```

**The model is not asked for tags.** It emits facets; a tag is earned by
embedding a facet and matching it against tag definitions on the same dim, so
the tag inherits that facet's verified span as evidence. A label the model
simply asserts has none, and no gate can give it any. The ingestion prompt does
not even list the catalogue — that cost 2,223 characters on every event, grew
with the vocabulary, and produced zero usable rows in 92 events.

**Conversation** (hot path) splits one message into three kinds of signal that
travel different routes, then merges the result with what earlier turns
established:

```
message → extract (1 LLM call) → deterministic gates
   │
   ├─ hard slots (city/date/price) ──────────► SQL WHERE
   ├─ negations ─────────────────────────────► NOT EXISTS
   └─ vibe facets ─► embed (bge-m3) ─┬─ tag ≥ 0.42 and carried
                                     │     → coverage 1.0, then cosine
                                     │       rescaled within the carriers
                                     └─ otherwise → cosine, same dim only
                                                    → rank → diversity → ≤5
```

Tag coverage replaced per-facet cosine on the dims that carry a vocabulary.
Membership in a tag is a reviewed fact with a real zero; cosine has neither —
two unrelated phrases on the same dim score **0.452**, which reads as "somewhat
relevant" and is not. Cosine remains the only option on dims with no tags, and
those are the ones the agent is worst at.

`POST /api/agent/search` runs one stateless turn. `POST /api/agent/chat` adds
memory keyed by `sessionId` in Redis (45-minute TTL) and a stage machine —
`BROWSING → FOCUSED → CONFIRMING`. Both are public: the funnel exists to collect
a signal from someone who has not committed to anything, and a login wall on the
first message loses exactly those people.

### Hard limits

These are load-bearing. Several were learned by breaking them.

**The model never emits a fact about an event.** Not a time, price, or venue. It
handles ids and vocabulary; every rendered field is read from the database. A
model that writes a showtime will eventually write a wrong one.

**Negation never reaches a vector.** `"not too crowded"` embeds *next to*
`"crowded"`, not far from it — embeddings have no notion of negation. It is
extracted as an excluded tag and applied as `NOT EXISTS`. Left in the vibe text,
it returns precisely what the person ruled out.

**Anything SQL can decide exactly stays out of the vector.** City, date, price.
`"new york"` inside an embedding matches a Boston event whose copy mentions New
York in passing.

**Proper nouns are matched, not measured.** A name carries no mood, and
distilling it destroys it — `"Taylor Swift"` embedded into a vibe vector returns
everything except the Taylor Swift shows. Named artists, venues and shows take a
separate literal-lookup path.

**Every extracted facet must quote its source.** The model returns the exact
span it derived each facet from, and Java rejects it if that span does not
appear verbatim in the description. This is the strongest gate precisely because
it is dumb: inventing a plausible facet is easy for a small model, inventing a
character sequence that happens to appear in a specific paragraph is not.

**The model's own confidence is ignored.** It is emitted and discarded. An 8B
model reports 0.95 for a fabricated facet as readily as for a sound one, so
admitting it as evidence would launder the exact failure the gates exist to
catch. Auto-approval is earned by deterministic checks or not at all.

**City is never relaxed.** When a search comes back too thin the constraints
widen in a fixed order — price, then time window, then exclusions — and each
step is reported back. City is not in that list: a show in the wrong city is not
a worse answer, it is a useless one.

**Date arithmetic happens in Java.** The model returns the person's own words
("this weekend"); a real clock and a real zone resolve them. Asked to compute a
date, a model answers confidently and wrongly, and the error is invisible.

**A slot stated this turn cannot be retracted this turn.** The model returns
`city: "tokyo"` together with `clearFields: ["city"]`, and applying the value
before the retraction let the retraction win — three conversation turns returned
byte-identical results while the logs said the city had been read correctly.
Decidable in Java, so it is decided there.

**An exclusion needs a negation in the sentence, and at most two of them.**
`excludeTags` is the only model output that acts as a hard filter: a facet must
quote its source and a tag assignment must survive review, but an exclusion
deletes events on the model's word alone. Measured across 57 queries, the
distribution was bimodal with nothing between — three requests excluded exactly
one tag and all three were right; seven excluded five to ten of the ten tags and
all seven were wrong. `"live music, nothing electronic"` excluded all ten
*including* `live-music` and cut the candidate set from 64 events to 6. A list
longer than two is discarded whole rather than trimmed: once the model has
enumerated the vocabulary, no subset of it is a reading of the sentence.

**Ranking has an explicit tiebreak.** Equal scores are structural here — the
same show runs in several cities with identical facets — and the sort is stable,
so tied rows inherited whatever order Postgres happened to return. With the
diversity cap on top, that turned an arbitrary order into a different result
*set*: 22 of 57 queries returned different answers on two runs of the same build
against the same data. Extraction was identical in all 57, so the variation was
entirely in ranking. Sorting by `(matched, score, id)` makes it reproducible.

**A shortlist shows a show once.** The category-and-time diversity bucket cannot
enforce that and works against it — two dates of one show at different times of
day land in different buckets, so the cap meant to stop repetition separates the
repeats and admits both. A request for something to take the children to came
back as one correct answer followed by the same technology conference three
times. Searches by name are exempt: someone typing an artist's name wants their
dates.

**Rows say whether they answer the request.** The shortlist always reaches for
five, so a request only one event satisfies still returns four more behind it —
**11% of all slots** on the evaluation set are filler. Each hit carries
`matched`, and rows are ordered matched-first so a client can cut where it turns
over. When the request could only be scored by cosine every row reports
`matched: true`, because cosine has no zero to divide on and inventing a
boundary there would hide rows on a number that does not mean what it looks
like.

**Ordinal references are an array lookup.** `"the second one"` indexes the
previous turn's result list in Java. Asked to recall what was second, a model
drifts as the conversation lengthens and the person silently gets the wrong
event.

### Validation pipeline

Five gates. The first four are deterministic — no model, no vector, no threshold
on the strongest one — and run before anything is embedded, so most fabrication
dies at zero inference cost.

| # | Gate | Blocks | Outcome |
| --- | --- | --- | --- |
| 1 | Shape | dim outside the closed vocabulary | reject |
| 2 | **Grounding** | cited span absent from the description | reject |
| 3 | Overlap | span is real but the facet is about something else | reject |
| 4 | Contradiction | scale claim the ticket count disproves | reject |
| 5 | Dim (vector) | atmosphere content filed under format | review¹ |

¹ "Review" is aspirational: there is no review screen, so a held facet stays
held. See *A gate with no review queue* below — the thirteen it was holding were
the most discriminating facets in the catalogue.

Rejections are kept in `facet_rejection` rather than dropped — the distribution
across reasons is the only honest measure of how much the model is inventing,
and tells you where to fix the prompt. A database `CHECK` constraint enforces
the grounding rule independently of whether application code remembered to call
the validator.

Gate 4 only fires in one direction: a large ticket count can disprove "intimate
room", but a small one cannot disprove "stadium" — ticket count is a lower bound
on venue size, not a measurement of it.

### The tag vocabulary

Ten tags: eight carry a dim and are matched against facets, two carry none and
are reachable only by exclusion (`headliner`, `late-night` describe an artist's
fame and a start time, neither of which is a dimension of the experience).

**Java seeds, the database decides.** `Taxonomy.TAGS` fills an empty database
and is then outranked by it: a reviewer who finds no tag fits a facet creates
one, and it takes effect on the next boot without a code change. `TagCatalog`
reads the live vocabulary for the query prompt and the `excludeTags` enum, so a
reviewer-created tag is excludable the moment it exists.

That boundary was learned the hard way. All fifteen original tags were written
in a single commit **fifteen hours before the first event was ingested**, so
every one was a guess about what a ticketing catalogue might hold. Eight guesses
matched the corpus; six did not, and an empty tag is not inert — across 92
events those six entered 173 candidate shortlists, took first place ten times
with every one of those wrong (a Formula 1 race tagged `workshop` on a
0.495-to-0.495 tie), and were approved zero times. One of them captured
*"somewhere I can learn something"* at 0.594 and, carried by nothing, returned no
events and erased the request's only signal. They were retired in `V9`. Both
tags that came through review instead — `professional`, `broadcast` — are
carried by events, because that flow starts from a facet nothing covers and so
cannot produce an empty tag.

A tag is embedded from **name + description + examples**, 244–329 characters,
never from its slug. Measured against *"a small room, close to the performer,
only a hundred people"*: the slug `intimate` scored 0.556 and lost to
`live-music`; the full definition scored 0.819 and won.

A dim needs at least two tags or matching it is a default rather than a
decision. `family-kids` was alone on `audience`, took all seventeen audience
facets, and twelve were wrong — including *"developers, engineers, and
technology enthusiasts"*. `TagSynchronizer` warns at startup when a dim has one.

### Measured behaviour

92-event demo catalogue, 57-query evaluation set. All figures from real runs.

**Ingestion**

| | |
| --- | --- |
| Events with ≥2 usable facets | **73%** (§15.1 threshold is 60%) |
| Facets kept / rejected | 264 / 174 (40% rejected) |
| Rejection reasons | 71% span-drift, 29% outright fabrication |
| Facets per event | 3.4 average, 1–8 range |
| Ingestion throughput | ~14 s per event (Metal GPU, model warm) |

**Tag assignment**, after reviewing all 368 candidate pairs by hand:

| | |
| --- | --- |
| Approved / rejected | 92 / 276, across 61 events |
| Chosen at rank 1 / 2 / 3 | 42 / 6 / 2 |
| Facets where no tag fitted | 16 |
| Auto-accept at 0.495 vs the hand review | 81% precision, 83% recall |

There are two thresholds, both measured rather than chosen, because they score
different text: **0.495** at ingest, where a facet carries its span, and
**0.42** at query time, where it is one distilled phrase. The ingest curve
against the hand verdicts has one knee; on the query side every one of the 23
matches at or above 0.42 is correct. Eight of the fifty chosen tags sat at rank two or three — one of
them an exact 0.495-to-0.495 tie — which is why the shortlist is stored rather
than only the winner.

**Retrieval**, precision@5 scored by distinct show:

| Group | Cases | p@5 | Path taken |
| --- | --- | --- | --- |
| City | 6 | **97%** | SQL |
| Temporal | 2 | 70% | SQL |
| Proper noun | 8 | 67% | literal name lookup |
| Negation | 5 | 40% | tag + exclusion gates |
| Combined | 6 | 40% | mixed |
| Genre | 8 | 30% | tag + within-group cosine |
| Vibe | 11 | 18% | cosine, mostly |
| Adversarial | 7 | 11% | mixed |
| **Overall** | **53** | **40%** | **13 of 53 perfect** |

Two runs of the same build return identical results on all 57 queries.

The perfect-case count is the more honest of the two figures. precision@5 is
capped by how many right answers the catalogue holds — a request only one event
satisfies can never exceed 20% however well it ranks — while "every expected
show was surfaced" measures the ranking itself. It went 7 → 13 as the tag path
was built out.

Scoring by distinct show, not by event id, is deliberate: 45 of 53 cases list
several dates of one show among their expected ids, so an id-level score
measures how many duplicates a shortlist emits rather than how well it ranks.

### Where it fails, and why

**The ordering in that table is the finding.** The more of a request SQL can
decide, the better the answer. Every group above 40% is carried by a structured
field; every group below is carried by a vector.

**Vibe (18%) is a data limit, not a matching one.** Those requests land on
`atmosphere` and `physical`, which carry no tags, so they fall to cosine. The
catalogue holds **four distinct `atmosphere` values across 92 events** —
descriptions talk about history and awards, not about what attending feels like.
Asked for *"live music in hanoi"*, where no Hanoi event is live music, cosine
returns a stage musical at 0.575 and a basketball game at 0.539, because
`"stage **musical**"` is lexically near `"live **music**"`. There is no threshold
that separates those from a real match, which is why the agent reports them
rather than hiding them.

**Genre (30%) was mostly the tag path hiding evidence the system already had.**
Tag membership is binary, so a request resolving to `live-music` scored all 19
carriers identically and the choice of five fell to recency and popularity. But
the discriminating word usually survives into the facet — measured across the
catalogue, **36 of 43 events whose description contains their genre keep it in a
facet** (`ballet performance`, `tennis tournament`, `three-stage knockout
qualifying session`). Coverage was throwing that away.

Ranking carriers among themselves by cosine recovers it, and the separation is
clean: asked for `"ballet"`, the two ballets score 0.615 and 0.556 against 0.399
for the musicals; asked for `"tennis"`, Wimbledon scores 0.571 against 0.461 for
the next sport.

Rock is the exception that misled the first attempt at this. It is the one genre
of eleven whose facets do not contain the word — Metallica's reads *"two shows
with different setlists and supporting acts"* while the description says
*"modern rock history"* — so no ranking of those facets could have worked.
Testing the idea on `"rock concert"` alone produced the wrong conclusion, and it
stood for several rounds.

**Absolute cosine is unusable even as a tiebreak.** Within one coverage group the
values are compressed — the gap that matters between the tennis event and the
next sport is 0.11 — and after the tiebreak and semantic weights that becomes
0.017 in the final score, against 0.40 for recency and popularity together. The
correct answer ranked last of five. Raising the weight cannot fix it: it would
have to exceed 6 to outrun the clock, and anything at or above 1.0 lets a better
example of one facet outrank covering two. The group is rescaled to 0..1
instead, keeping the ranking and discarding the magnitude — which is the only
part of a cosine that means anything when 0.452 is the floor for two unrelated
phrases.

**A threshold calibrated on one text length does not transfer to another.** The
tag-match threshold was measured on ingest facets — `value` plus its span,
around 96 characters — and reused unchanged on query facets, which are often a
single word. `ballet` scored 0.442 against `performing-arts` and `tennis` 0.445
against `sports`: both correct, both discarded, both falling through to a cosine
that answered `"ballet"` with a Bruno Mars concert. Measured over the 29 distinct
query facets the evaluation set produces, **every one of the 23 at or above 0.42
is correct** and wrong matches begin below it, so the query side has its own
number.

**A gate with no review queue is a permanent deletion.** The dim gate routes a
facet it cannot place to review rather than rejecting it — but nothing ever
reviews, so `approved_at` stays null and the scoring queries skip it forever.
The 13 facets it was holding turned out to be among the most discriminating in
the catalogue: `ballet performance` ×3, `tennis tournament` ×2, `classical music
performance` ×2, and `electric and intense`, one of only four `atmosphere`
values that exist. Wimbledon is the only tennis event in the catalogue and its
only format facet was held, so `"tennis"` could not be answered correctly however
well every other layer worked.

Twelve of the thirteen belonged to events carrying an **approved tag derived from
that same facet** — the conclusion was accepted while its evidence was held. The
facets were reviewed and approved by hand; the gate's behaviour on short,
specific facets is unchanged and will hold the next ones the same way.

**Embeddings cannot read negation, antonyms, or magnitude.** Measured on the
running model: `"not crowded"` scores **0.771** against `"crowded"`; `"calm"`
scores 0.489 against `"high-energy"` while two unrelated phrases score 0.452;
`"1.8 million fans"` matches `intimate` over `large-scale`. A `seated`/`standing`
pair was written, embedded and withdrawn — `standing` beat `seated` on every
facet in the corpus including *"grandstand setting"* (0.535 to 0.488), whose own
definition names grandstands, with margins of 0.002 to 0.05. A dim whose answers
are opposites of each other cannot be decided by cosine.

**Reasoning mode does not fix it.** `qwen3:8b` with `think: true` was measured
against `think: false` on the same build: median latency **6.1 s → 38.8 s**
(×6.4, worst case 105 s), for five extractions improved and three made worse.
It keeps `"basketball game"` intact where the default distils it to `"sports"`,
and it drops spurious facets — but `"rock"`, `"kpop"` and `"soccer"` are still
lost, and it invents facets of its own (`"kpop concert"` gained an atmosphere and
a scale nobody asked for). End to end it is a wash — **34% → 31%** precision@5
on the 18 evaluation cases both configurations completed, 2 perfect against 3.
The default stays off: 6.4× the latency for no measurable gain, and a 105 s
worst case exceeds every timeout in the request path.

### Operational notes

- **Ingestion is single-threaded on purpose.** Every message costs an LLM call
  taking seconds; throughput is bounded by the model, not thread count.
  `max.poll.interval.ms` is raised to 10 minutes so the broker does not decide
  the consumer is dead mid-batch.
- **Failures are split by whether retrying helps.** Postgres or Ollama down →
  do not ack, let Kafka redeliver. A description the model cannot parse → ack
  and move on, or one bad event stalls the partition forever.
- **`searchable` is a curation gate, not a business one.** An OPEN event whose
  facets nobody accepted stays invisible to the agent. Recommending on the
  strength of unreviewed facets is worse than not recommending.
- **Re-ingest replaces machine rows and leaves human rows alone**, so editing a
  description upstream never discards a reviewer's corrections.
- **Four layers hold a timeout for one path, and they disagree.** nginx allows
  120 s, the gateway's HTTP client 30 s, its circuit breaker treats 180 s as
  slow, and agent-service gives Ollama 180 s. The 30 s is the only one that
  binds, and nobody chose it with a language model in mind — it is invisible
  until a turn runs long, and then every request returns 502 while the service
  is still working and still writing conversation state. Per-route metadata
  would fix it; the Spring Cloud version here does not expose it.
- **`agent_db` is disposable.** It is a derived read model; replay the topic and
  it rebuilds, minus the human review decisions.

## Search subsystem

Dedicated `search-service` exposes two public REST endpoints
(`/api/search/events` and `/api/search/events/suggest`) backed by an
Elasticsearch derived index synced over the `event.search.indexed` Kafka
topic. Multi-field BM25 with boosting, edge-ngram autocomplete, and
typo-tolerant matching. A Caffeine cache with admission filter cuts ES
load on hot prefixes ~30-fold; outages here degrade discovery without
affecting ticket sales (Postgres remains the source of truth).

▶︎ **[`search-service/README.md`](search-service/README.md)** — architecture, indexing pipeline, load-shaping for `/suggest`, what's explicitly out of scope.

Sibling to the [recommendation agent](#recommendation-agent) above: same Kafka
topic, two independent read models. Keyword search answers "find me *this*";
the agent answers "find me *something like this*". Neither knows the other
exists.

## Hot-event detection

Per-event view-counter + watchdog that proactively flags events surging in
traffic. Complements the reactive Caffeine cache: LFU is per-ticket and
adapts to actual access patterns, while this signal is per-event and
catches the "5,000 distinct tickets, each viewed once" case LFU misses.

```
GET /api/tickets/events/{id}/tickets
   ↓
ticket-service: INCR event-views:{id}  +  EXPIRE = 60s   (rolling window)

EventHotnessWatchdog (every 10s, in ticket-service):
   SCAN event-views:*  →  MGET all counters
   count ≥ 50  AND not currently HOT  →  SET event-hot:{id} EX 120
                                          publish hot=true to Kafka
   count ≤ 20  AND currently HOT       →  DEL event-hot:{id}
                                          publish hot=false
   (else)                              →  refresh event-hot TTL

Kafka: event.hotness.changed (transitions only — minimal traffic)
   ↓
order-service: EventHotnessConsumer  →  log transition  (v1: log-only)
```

**Hysteresis (enter > exit) prevents flapping** when traffic hovers near the
threshold; **120s safety TTL** on the HOT flag means a dead watchdog
can't leave events stuck hot forever.

Tunable in `application.yml` without rebuild:

| Knob | Default | Purpose |
| ---- | ------- | ------- |
| `hotness.enter-threshold` | 50 views/min | Cross going up → HOT |
| `hotness.exit-threshold`  | 20 views/min | Cross going down → not-HOT |
| `hotness.window-seconds`  | 60           | Rolling window (also Redis TTL on counter) |
| `hotness.tick-seconds`    | 10           | Watchdog evaluation cadence |
| `hotness.flag-ttl-seconds`| 120          | Safety expiry on the HOT flag |

**v1 consumer behaviour: log-only.** Two future hooks plug in at
`EventHotnessConsumer` without producer-side changes:
1. On `hot=true`: pre-fetch the event's tickets into `order-ticket-status`
   so even cold-pod first-reads land in cache.
2. On `hot=true`: bump the gateway's per-path rate limit so legitimate
   buyers aren't throttled by the surge.

## Request idempotency

Stripe-pattern HTTP idempotency on `POST /api/orders` and
`POST /api/secondary/listings`: client sends a UUID-per-click in an
`Idempotency-Key` header, the server (via a `common-lib` filter) dedupes by
`(userId, key)` in Redis with a 24h TTL. Three rapid POSTs of the same
intent settle to exactly one row in the database; same key with a
different body returns 422.

Layered above existing Tier-3 safety nets (orderId-keyed partitioning,
Redis SETNX, optimistic locking, watchdog) so even DevTools-crafted retries
with fresh UUIDs can't double-effect.

▶︎ **[`docs/IDEMPOTENCY.md`](docs/IDEMPOTENCY.md)** — full three-tier breakdown, what each tier catches, and what's deliberately out of scope.

## Deep dives

Cross-cutting topics that span more than one service live in their own docs:

| Topic | Where |
| ----- | ----- |
| Performance, scaling, consumer concurrency, stall behaviour | [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) |
| Request idempotency (UI + HTTP filter + saga safety nets)   | [`docs/IDEMPOTENCY.md`](docs/IDEMPOTENCY.md) |
| Saga animated flow (interactive)                            | [animated-flows.html](https://htmlpreview.github.io/?https://github.com/xol60/ticketing-platform/blob/main/docs/animated-flows.html) |
| Saga static reference                                       | [diagrams.html](https://htmlpreview.github.io/?https://github.com/xol60/ticketing-platform/blob/main/docs/diagrams.html) |
| Stress test methodology + findings                          | [`tests/README.md`](tests/README.md) |
| Agent retrieval eval — 57 hand-labelled cases with `rejectIds` | [`tests/agent-eval.json`](tests/agent-eval.json) |

Per-service architecture lives in each service's own README — see [Project structure](#project-structure).

## Architecture decisions

### Auth — gateway-only, internal trust model

JWT is validated once at the API Gateway using a two-layer cache (L1 in-process LRU + L2 Redis).
Internal services receive `X-User-Id`, `X-User-Role`, `X-Trace-Id` headers — no JWT re-validation.
Internal services are network-isolated: only reachable from within the Docker network.

Two path categories bypass auth:
- **`publicPaths`** (all HTTP methods) — `/api/auth/login`, `/api/auth/register`,
  `/api/auth/refresh`, `/actuator/health`.
- **`publicGetPaths`** (GET only) — `/api/tickets/events`, `/api/tickets`,
  `/api/search/`, `/api/secondary/listings`, `/api/pricing/rules`. The
  method-aware check means anonymous users can browse the catalog and
  search results, while the corresponding POST / PATCH / DELETE on the
  same prefix still require a token. Implemented in `AuthFilter` by checking
  `HttpMethod.GET == request.getMethod()` before the public-GET prefix match.

### Saga — orchestration pattern

The `saga-orchestrator` drives each transaction step explicitly via Kafka commands.
State is persisted in Redis (`saga:{sagaId}`) with TTL-based watchdog for stuck sagas.
Compensation runs in reverse order on any step failure.

### Database — master/slave read routing

All writes go to `postgres-master`. Reads follow: L1 cache → L2 Redis → postgres-slave.
Each service has its own database (bounded context isolation — no cross-service SQL).

### Agent — local model, guarded rather than trusted

Inference runs on `qwen3:8b` in Ollama rather than a hosted frontier model. The
trade is deliberate: the project clones and runs with no API key, no event data
leaves the machine, and total cost is electricity — against a model that
fabricates far more readily.

That trade only works because the fabrication is *catchable*. Every extracted
fact carries the span it came from and is discarded when the span is absent, so
the guard costs a string comparison rather than a second model. Confidence
scores are ignored entirely: an 8B model reports 0.95 for an invented facet as
readily as a sound one, so admitting that number as evidence would launder the
exact failure the guards exist to catch.

The measured cost is a 40% rejection rate. The measured benefit is that what
survives is grounded in text a reviewer can point at.

### Agent — request/response, not WebSocket

The agent is a chat interface, which usually argues for a socket. Here it does
not, for a reason that is structural rather than about load: **the model's output
is not what the user reads.** It emits a JSON patch of slots and facets; the
answer on screen is rendered from Postgres afterwards. There is no token stream
to show, and first byte and last byte land at the same moment.

Concurrency does not argue for it either — measured, three simultaneous turns
serialise ~4.5 s apart, because Ollama runs one generation at a time per model.
Holding sockets open would not make that faster; it would invite more concurrent
work than the backend can serve, and bypass the gateway's per-request rate limit
on the most expensive endpoint on the platform.

Server-push does exist in this codebase, where it is genuinely warranted —
order-service and pricing-service both use SSE, because there the server has news
the client did not ask for. The agent never does: it only ever answers.

### Circuit breaker + rate limiter — gateway only

Resilience4j circuit breaker wraps each upstream service independently.
Rate limiter uses Redis sliding window counters keyed by `IP:userId`.

Per-path overrides via `gateway.rate-limit.path-overrides`:

| Path prefix     | Limit (r/s) | Why |
| --------------- | ----------- | --- |
| `/api/auth`     | 5           | Tighter on login / refresh — credential-stuffing surface |
| `/api/search`   | 60          | Autocomplete-as-you-type is naturally bursty; anonymous users from one NAT IP share the bucket. Higher limit safe because the search-service Caffeine cache already protects ES. |
| `/api/agent`    | 2           | Tightest on the platform. One turn costs an LLM call plus one or more embeddings on a single-threaded local model, and unlike search it cannot be absorbed by a cache. Two per second is already faster than a person types. |
| (default)       | 20          | Everything else |
