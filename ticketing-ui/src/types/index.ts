// ─── Auth ────────────────────────────────────────────────────────────────────
export type Role = 'USER' | 'ADMIN' | 'EVENT_OWNER' | 'SUPPORT';

export interface User {
  id: string;
  email: string;
  username: string;
  role: Role;
  tenantId?: string;
  enabled: boolean;
  emailVerified: boolean;
  createdAt: string;
}

/**
 * Backend returns a flat envelope, not a nested `user`. Login flow:
 *   1. POST /api/auth/login → store tokens
 *   2. GET  /api/auth/me    → hydrate the full {@link User} (we use this rather
 *                              than the flat fields below so role / email /
 *                              verified state always come from one canonical
 *                              source).
 */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  accessTokenExpiresIn: number;
  userId: string;
  username: string;
  role: Role;
}

// ─── Events & Tickets ────────────────────────────────────────────────────────
export type EventStatus = 'DRAFT' | 'OPEN' | 'CLOSED' | 'CANCELLED';

export interface Event {
  // ── Identity / lifecycle ───────────────────────────────────────────────────
  id: string;
  /** Server-side field name (some endpoints return `eventId` instead) */
  eventId?: string;
  name: string;
  status: EventStatus;
  salesOpenAt: string;
  salesCloseAt: string;
  eventDate: string;
  createdAt?: string;

  // ── Searchable metadata (added with the search-service / ES integration) ──
  // Every field is optional — backend allows partial events and migration V6
  // backfilled existing rows with NULLs.
  primaryArtist?: string;
  venueName?: string;
  venueCity?: string;
  shortDescription?: string;
  fullDescription?: string;
  /** CONCERT | SPORTS | THEATER | CONFERENCE | ... */
  category?: string;
  /** Free-form sub-genre — POP, ROCK, EDM, JAZZ, BASKETBALL, MUSICAL, ... */
  genre?: string;
}

// ─── Search subsystem (Elasticsearch read-only derived index) ────────────────

/** One row in {@link EventSearchPage.hits} — light projection of EventDocument. */
export interface EventSearchHit {
  id: string;
  name: string;
  primaryArtist?: string;
  venueName?: string;
  venueCity?: string;
  category?: string;
  genre?: string;
  eventDate?: string;
  /** Raw BM25 score from Elasticsearch — useful for debugging boost tuning. */
  score: number;
}

/** Paged envelope returned by {@code GET /api/search/events}. */
export interface EventSearchPage {
  query: string;
  totalHits: number;
  from: number;
  size: number;
  hits: EventSearchHit[];
}

/** One row in the autocomplete dropdown. */
export interface AutocompleteSuggestion {
  eventId: string;
  text: string;
  primaryArtist?: string;
}

// ─── Phase 3b — paged ticket-list with batched pricing ───────────────────────

/** One row in {@link AvailableTicketsPage.tickets} — light projection. */
export interface AvailableTicketSummary {
  id: string;
  section?: string;
  row?: string;
  seat: string;
  facePrice: number;
  /** facePrice × surgeMultiplier (already rounded server-side). */
  effectivePrice: number;
}

/**
 * Page envelope returned by
 * {@code GET /api/tickets/events/{eventId}/tickets?page=X&size=Y}.
 *
 * Collapses the per-ticket pricing N+1 by issuing a single pricing-service
 * lookup per page (Caffeine-cached 30s on the server).
 */
export interface AvailableTicketsPage {
  eventId: string;
  surgeMultiplier: number;
  totalAvailable: number;
  size: number;
  page: number;
  tickets: AvailableTicketSummary[];
}

export type TicketStatus = 'AVAILABLE' | 'RESERVED' | 'CONFIRMED';

export interface Ticket {
  id: string;
  eventId: string;
  eventName: string;
  section?: string;
  row?: string;
  seat: string;
  status: TicketStatus;
  facePrice: number;
  lockedPrice?: number;
  createdAt: string;
}

// ─── Orders ──────────────────────────────────────────────────────────────────
export type OrderStatus =
  | 'PENDING'
  | 'PRICE_CHANGED'
  | 'CONFIRMED'
  | 'FAILED'
  | 'CANCELLED';

export interface Order {
  id: string;
  userId: string;
  ticketId: string;
  sagaId: string;
  status: OrderStatus;
  requestedPrice: number;
  pendingPrice?: number;
  finalPrice?: number;
  paymentReference?: string;
  failureReason?: string;
  createdAt: string;
  updatedAt: string;
}

// ─── Pricing ─────────────────────────────────────────────────────────────────
export interface PriceRule {
  id: string;
  eventId: string;
  eventName?: string;
  surgeMultiplier: number;
  maxSurge: number;
  demandFactor: number;
  totalTickets: number;
  soldTickets: number;
  eventDate?: string;
}

// ─── Reservations ────────────────────────────────────────────────────────────
export type ReservationStatus = 'QUEUED' | 'PROMOTED' | 'EXPIRED' | 'CANCELLED';

export interface Reservation {
  id: string;
  ticketId: string;
  userId: string;
  status: ReservationStatus;
  queuedAt: string;
  promotedAt?: string;
  expiresAt: string;
}

export interface WaitlistPosition {
  position: number;
  reservationId: string;
  estimatedWait?: string;
}

// ─── Payments ────────────────────────────────────────────────────────────────
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';

export interface Payment {
  orderId: string;
  status: PaymentStatus;
  amount: number;
  paymentReference?: string;
  failureReason?: string;
  createdAt: string;
}

// ─── Secondary Market ────────────────────────────────────────────────────────
export type ListingStatus = 'ACTIVE' | 'SOLD' | 'CANCELLED';

export interface Listing {
  id: string;
  ticketId: string;
  sellerId: string;
  eventId: string;
  askPrice: number;
  status: ListingStatus;
  purchasedByUserId?: string;
  purchasedOrderId?: string;
  createdAt: string;
}

// ─── Notifications ───────────────────────────────────────────────────────────
export interface NotificationLog {
  id: string;
  userId: string;
  type: string;
  subject: string;
  body: string;
  sentAt: string;
  read: boolean;
}

// ─── API Response wrapper ────────────────────────────────────────────────────
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  timestamp?: string;
}

// ── Recommendation agent ─────────────────────────────────────────────────────

/**
 * One suggested event.
 *
 * Every field here comes from the database, not from the model. The agent
 * handles ids and vocabulary; it never writes a time, price or venue, because a
 * model that writes a showtime will eventually write a wrong one.
 */
export interface AgentHit {
  eventId: string;
  name: string;
  primaryArtist?: string;
  venueName?: string;
  venueCity?: string;
  category?: string;
  startAt?: string;
  priceMin?: number;
  priceMax?: number;
  /**
   * The distilled facts that differ most across this result set — shown instead
   * of a per-result explanation.
   *
   * An explanation would have to invent a reason the ranker cannot supply, which
   * is a surface for the model to make things up on. Showing the fields that
   * actually vary lets the reader see the difference rather than be told about it.
   */
  differentiators?: string[];
  score: number;
}

export interface AgentSearchResponse {
  hits: AgentHit[];
  /** Events matching the filter, not the shortlist size. Drives the narrowing offer. */
  totalMatched: number;
  offerNarrowing: boolean;
  /**
   * What was widened to find these, in order. Empty on a normal search.
   * Surfaced because a result set that silently ignored a stated budget is
   * worse than an empty one.
   */
  relaxations: string[];
  /** False when ranking fell back to popularity and proximity with no mood signal. */
  usedVibe: boolean;
}

/** BROWSING → FOCUSED → CONFIRMING. */
export type AgentStage = 'BROWSING' | 'FOCUSED' | 'CONFIRMING';

/**
 * What the conversation currently believes.
 *
 * Rendered rather than hidden: slots accumulate silently across turns, and a
 * search narrowed by a constraint from four turns ago is indistinguishable from
 * a broken one unless the constraint is visible.
 */
export interface AgentActiveFilters {
  city?: string;
  dateExpression?: string;
  priceMax?: string;
  excludeTags?: string[];
  vibe?: string[];
  turnCount: number;
}

/**
 * The end of the funnel: an event id and a link.
 *
 * The agent holds no ticket and creates no order — `deepLink` points at the
 * ordinary event page, where the existing checkout takes over. `available` is a
 * courtesy check against a projection that can be seconds stale; checkout
 * re-validates for real.
 */
export interface AgentHandoff {
  eventId: string;
  deepLink: string;
  available: boolean;
  reason?: string;
}

export interface AgentChatResponse {
  stage: AgentStage;
  /** Present while browsing. */
  hits?: AgentHit[];
  /** Present once one event has been picked. Mutually exclusive with `hits`. */
  focused?: AgentHit;
  totalMatched: number;
  offerNarrowing: boolean;
  relaxations: string[];
  usedVibe: boolean;
  activeFilters: AgentActiveFilters;
  /** Only on the turn the person asks to buy. */
  handoff?: AgentHandoff;
}
