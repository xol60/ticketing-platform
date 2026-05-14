# Ticketing Platform

Microservice ticketing system — Java 21 + Spring Boot 3.2 + Kafka + Redis + Postgres.

## Prerequisites

| Tool           | Version |
| -------------- | ------- |
| Java           | 21+     |
| Maven          | 3.9+    |
| Docker         | 24+     |
| Docker Compose | 2.24+   |

## Project structure

```
ticketing-platform/
├── common-lib/              # Shared events, DTOs, exceptions
├── api-gateway/             # Reactive gateway — traceId, rate limiter, circuit breaker, auth cache
├── auth-service/            # JWT issue + refresh
├── ticket-service/          # Aggregate root, inventory lock
├── order-service/           # Workflow orchestrator
├── saga-orchestrator/       # Distributed transaction middleware
├── pricing-service/         # Dynamic pricing + Redis pub/sub
├── reservation-service/     # Waitlist queue
├── payment-service/         # External payment + DLQ + admin alert
├── secondary-market-service/# Ticket resale
├── notification-service/    # Email / push
└── docker/
    ├── kafka/               # Topic creation script
    ├── postgres/            # Master config + slave init
    ├── redis/               # redis.conf
    └── nginx/               # nginx.conf
```

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
| Postgres Master      | 5432 (internal) / 5436 (host)  |
| Redis                | 6379                           |
| Kafka                | 9092 (internal) / 29092 (host) |

## Kafka topics

All saga-flow topics use **3 partitions** to match `concurrency=3` on each consumer. Records
are partitioned by `orderId` so all events for the same order are processed in producer-send
order by exactly one consumer thread. `payment.dlq` and `auth.security.alert` keep
**1 partition** intentionally (see notes below).

### Command-topic consolidation

Two domains use a **single command topic per service** instead of one topic per command type.
This is the most important ordering decision in the system:

| Service         | Unified topic   | Carries                                                                |
| --------------- | --------------- | ---------------------------------------------------------------------- |
| ticket-service  | `ticket.cmd`    | `TicketReserveCommand`, `TicketConfirmCommand`, `TicketReleaseCommand` |
| payment-service | `payment.cmd`   | `PaymentChargeCommand`, `PaymentCancelCommand`                         |

**Why a single topic?** All commands for the same `orderId` must be processed in send order.
With separate topics (`ticket.reserve.cmd` / `ticket.release.cmd` / `ticket.confirm.cmd`),
each topic has its own partition assignment, and two consumer threads could pick up a
`Release` and a `Confirm` for the same order concurrently — letting the `Release` win the
race produces a `TicketReleased` event followed by a `Confirm` that finds an `AVAILABLE`
ticket and emits a spurious `TicketReservationFailed`. For payments the symmetric bug is
worse: a `Cancel` processed before its preceding `Charge` would be silently dropped,
leaving the customer charged with no record of the cancel.

With a single topic keyed by `orderId`, both commands land on the **same partition** and
are consumed **sequentially by one thread** — strict per-order ordering is preserved
without any application-level locking.

The legacy split topics (`ticket.reserve.cmd`, `ticket.release.cmd`, `ticket.confirm.cmd`,
`payment.charge.cmd`) are still created for broker compatibility with old offsets and DLQ
consumers, but no current code path produces to them.

### Topic catalog

| Topic                   | Producer                                | Consumer                                | Partitions | Key       |
| ----------------------- | --------------------------------------- | --------------------------------------- | ---------- | --------- |
| `order.created`         | order-service, secondary-market-service | saga-orchestrator                       | 3          | `orderId` |
| `order.confirmed`       | saga-orchestrator                       | order-service                           | 3          | `orderId` |
| `order.failed`          | saga-orchestrator                       | order-service                           | 3          | `orderId` |
| `order.cancelled`       | saga-orchestrator                       | order-service                           | 3          | `orderId` |
| `order.price.changed`   | saga-orchestrator                       | order-service                           | 3          | `orderId` |
| `order.price.confirm`   | order-service                           | saga-orchestrator                       | 3          | `orderId` |
| `order.price.cancel`    | order-service                           | saga-orchestrator                       | 3          | `orderId` |
| `ticket.cmd`            | saga-orchestrator                       | ticket-service                          | 3          | `orderId` |
| `ticket.reserved`       | ticket-service                          | saga-orchestrator                       | 3          | `orderId` |
| `ticket.released`       | ticket-service                          | saga-orchestrator, reservation-service  | 3          | `orderId` |
| `ticket.confirmed`      | ticket-service                          | saga-orchestrator, notification-service | 3          | `orderId` |
| `pricing.lock.cmd`      | saga-orchestrator                       | pricing-service                         | 3          | `orderId` |
| `pricing.locked`        | pricing-service                         | saga-orchestrator                       | 3          | `orderId` |
| `pricing.unlock.cmd`    | saga-orchestrator                       | pricing-service                         | 3          | `orderId` |
| `pricing.price.changed` | pricing-service                         | saga-orchestrator                       | 3          | `orderId` |
| `pricing.failed`        | pricing-service                         | saga-orchestrator                       | 3          | `orderId` |
| `price.updated`         | pricing-service                         | (SSE push to clients)                   | 3          | `eventId` |
| `payment.cmd`           | saga-orchestrator                       | payment-service                         | 3          | `orderId` |
| `payment.succeeded`     | payment-service                         | saga-orchestrator                       | 3          | `orderId` |
| `payment.failed`        | payment-service                         | saga-orchestrator                       | 3          | `orderId` |
| `payment.refunded`      | payment-service                         | saga-orchestrator                       | 3          | `orderId` |
| `payment.dlq`           | payment-service                         | notification-service                    | **1**      | `orderId` |
| `saga.compensate`       | saga-orchestrator                       | ticket-service                          | 3          | `orderId` |
| `reservation.promoted`  | reservation-service                     | order-service                           | 3          | `ticketId`|
| `event.status.changed`  | ticket-service                          | (subscribers)                           | 3          | `eventId` |
| `notification.send`     | any service                             | notification-service                    | 3          | `orderId` |
| `auth.security.alert`   | auth-service                            | notification-service                    | **1**      | `userId`  |
| `sale.flash`            | ticket-service                          | (subscribers)                           | 3          | `eventId` |

**Single-partition exceptions:**
- `payment.dlq` — DLQ replay must be strictly chronological across all orders, not just
  per-order, so admins can reconstruct the failure timeline.
- `auth.security.alert` — global ordering of suspicious-login events for forensics; volume
  is low enough that 1 partition isn't a throughput concern.

## Architecture decisions

### Auth — gateway-only, internal trust model

JWT is validated once at the API Gateway using a two-layer cache (L1 in-process LRU + L2 Redis).
Internal services receive `X-User-Id`, `X-User-Role`, `X-Trace-Id` headers — no JWT re-validation.
Internal services are network-isolated: only reachable from within the Docker network.

### Saga — orchestration pattern

The `saga-orchestrator` drives each transaction step explicitly via Kafka commands.
State is persisted in Redis (`saga:{sagaId}`) with TTL-based watchdog for stuck sagas.
Compensation runs in reverse order on any step failure.

### Database — master/slave read routing

All writes go to `postgres-master`. Reads follow: L1 cache → L2 Redis → postgres-slave.
Each service has its own database (bounded context isolation — no cross-service SQL).

### Circuit breaker + rate limiter — gateway only

Resilience4j circuit breaker wraps each upstream service independently.
Rate limiter uses Redis sliding window counters keyed by `IP:userId`.
