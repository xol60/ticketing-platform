# TicketHub — Frontend

React + TypeScript single-page application for the Ticketing Platform.
Serves two user roles: **Buyers** (browse events, purchase tickets, resell) and **Event Creators** (manage events, tickets, and dynamic pricing).

## Tech stack

| Layer | Library |
|---|---|
| Framework | React 18 + TypeScript |
| Build | Vite 5 |
| Styling | Tailwind CSS v4 |
| Routing | React Router v6 |
| Data fetching | TanStack Query v5 |
| HTTP | Axios (with JWT auto-refresh interceptor) |
| Real-time | EventSource (SSE) — order status tracking |

## Features

### Buyer (USER role)
- Register / Login — JWT-based auth, silent token refresh
- Browse events and available tickets with live dynamic prices
- Buy a ticket — full saga-driven checkout flow
- Real-time order tracker (SSE stream) with live status steps
- Price change modal — accept or reject mid-saga price updates with countdown timer
- Waitlist — join / leave queue for sold-out tickets, see queue position
- My Orders — list, detail, payment reference
- Resale Market — browse listings, buy resold tickets
- List your confirmed ticket for resale with custom ask price
- Notification bell — live unread count, dropdown history

### Event Creator (EVENT_OWNER role)
- Creator Dashboard — all events with ticket/sale stats at a glance
- Create / Open / Close / Cancel events
- Ticket Manager — add, edit, delete seats per event (section / row / seat / face price)
- Pricing Manager — set surge multiplier and max surge cap, live demand meter

## Project structure

```
src/
├── api/            Axios API clients (one file per service)
├── components/
│   ├── ui/         Button, Input, Card, Modal, Badge, Spinner
│   └── layout/     Navbar, Layout, NotificationBell
├── context/        AuthContext — user state, login/logout, role helpers
├── hooks/          useOrderStream — SSE hook for real-time order updates
├── pages/
│   ├── auth/       LoginPage, RegisterPage, ProfilePage
│   ├── events/     EventListPage, EventDetailPage
│   ├── orders/     TicketDetailPage, OrderTrackerPage, OrderListPage, OrderDetailPage
│   ├── secondary/  SecondaryMarketPage
│   └── creator/    CreatorDashboard, TicketManagerPage, PricingManagerPage
├── router/         AppRouter, PrivateRoute, RoleRoute guards
└── types/          Shared TypeScript types for all domain models
```

## Running locally (without Docker)

```bash
# 1. Start backend infrastructure (Postgres, Redis, Kafka + all services)
docker-compose up postgres-master redis kafka kafka-init \
  auth-service ticket-service order-service saga-orchestrator \
  pricing-service reservation-service payment-service \
  secondary-market-service notification-service -d

# 2. Start the UI dev server (hot-reload)
cd ticketing-ui
npm install
npm run dev
# → http://localhost:3000
```

The Vite dev server proxies all `/auth/*` and `/api/*` requests to the backend services automatically — no CORS configuration needed.

## Running in Docker

```bash
# Default
docker-compose up --build

# Development (all ports exposed + port 3000 for UI direct access)
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up --build

# Production (restart policies + resource limits)
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

| URL | Description |
|---|---|
| `http://localhost` | Full app via Nginx (UI + API) |
| `http://localhost:3000` | UI direct — dev mode only |

## Architecture notes

**Auth** — JWT is validated once at the API Gateway. All downstream API calls carry `X-User-Id` and `X-User-Role` headers injected by the gateway. The Axios interceptor in `src/api/client.ts` automatically refreshes the access token on `401` and retries the original request.

**Order tracking** — After placing an order, the UI opens an `EventSource` connection to `/api/orders/{id}/stream`. The backend pushes saga state transitions in real time. When a `PRICE_CHANGED` event arrives, the Price Change Modal auto-opens with a 6-minute countdown — the user must accept or reject before the saga watchdog cancels the order.

**Dynamic pricing** — Ticket prices are fetched from the Pricing Service every 10 seconds on the Ticket Detail page. A surge badge shows the percentage above face value when demand is high.

**Role-based routing** — `PrivateRoute` redirects unauthenticated users to `/login`. `RoleRoute` redirects buyers away from `/creator/*` routes.
