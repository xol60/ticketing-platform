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

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

// ─── Events & Tickets ────────────────────────────────────────────────────────
export type EventStatus = 'DRAFT' | 'OPEN' | 'CLOSED' | 'CANCELLED';

export interface Event {
  id: string;
  name: string;
  status: EventStatus;
  salesOpenAt: string;
  salesCloseAt: string;
  eventDate: string;
  createdAt: string;
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
