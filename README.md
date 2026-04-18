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

| Topic                   | Producer                                | Consumer                                |
| ----------------------- | --------------------------------------- | --------------------------------------- |
| `order.created`         | order-service, secondary-market-service | saga-orchestrator                       |
| `order.confirmed`       | saga-orchestrator                       | order-service                           |
| `order.failed`          | saga-orchestrator                       | order-service                           |
| `order.cancelled`       | saga-orchestrator                       | order-service                           |
| `order.price.changed`   | saga-orchestrator                       | order-service                           |
| `order.price.confirm`   | order-service                           | saga-orchestrator                       |
| `order.price.cancel`    | order-service                           | saga-orchestrator                       |
| `ticket.reserve.cmd`    | saga-orchestrator                       | ticket-service                          |
| `ticket.reserved`       | ticket-service                          | saga-orchestrator                       |
| `ticket.release.cmd`    | saga-orchestrator                       | ticket-service                          |
| `ticket.released`       | ticket-service                          | saga-orchestrator, reservation-service  |
| `ticket.confirm.cmd`    | saga-orchestrator                       | ticket-service                          |
| `ticket.confirmed`      | ticket-service                          | saga-orchestrator, notification-service |
| `pricing.lock.cmd`      | saga-orchestrator                       | pricing-service                         |
| `pricing.locked`        | pricing-service                         | saga-orchestrator                       |
| `pricing.unlock.cmd`    | saga-orchestrator                       | pricing-service                         |
| `pricing.price.changed` | pricing-service                         | saga-orchestrator                       |
| `pricing.failed`        | pricing-service                         | saga-orchestrator                       |
| `price.updated`         | pricing-service                         | (SSE push to clients)                   |
| `payment.charge.cmd`    | saga-orchestrator                       | payment-service                         |
| `payment.succeeded`     | payment-service                         | saga-orchestrator                       |
| `payment.failed`        | payment-service                         | saga-orchestrator                       |
| `payment.dlq`           | payment-service                         | notification-service                    |
| `saga.compensate`       | saga-orchestrator                       | ticket-service                          |
| `reservation.promoted`  | reservation-service                     | order-service                           |
| `event.status.changed`  | ticket-service                          | (subscribers)                           |
| `notification.send`     | any service                             | notification-service                    |

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
