import type { AxiosResponse } from 'axios';
import api from './client';
import type { Event, Ticket, ApiResponse, AvailableTicketsPage } from '../types';

/**
 * Backend's {@code EventStatusResponse} ships the primary key as {@code eventId}
 * while almost all UI code reaches for {@code event.id}. Rather than rewrite
 * dozens of call sites we normalise here — the returned object exposes both
 * fields aliased to the same value, so existing code that uses {@code event.id}
 * and any new code that uses {@code event.eventId} both work.
 *
 * <p>Without this mapping every {@code events.find(e => e.id === eventId)}
 * silently returned {@code undefined}, which is exactly the symptom that surfaced
 * as "the event detail page shows undefined / no tickets" in the wild.
 */
function normaliseEvent<T extends Event & { eventId?: string }>(raw: T): Event {
  return { ...raw, id: raw.id ?? raw.eventId ?? '', eventId: raw.eventId ?? raw.id };
}

export const ticketsApi = {
  // ── Events ──────────────────────────────────────────────────────────────────
  listEvents: () =>
    api.get<ApiResponse<Event[]>>('/api/tickets/events')
       .then((r: AxiosResponse<ApiResponse<Event[]>>) => r.data.data.map(normaliseEvent)),

  createEvent: (body: {
    name: string;
    eventDate: string;
    salesOpenAt: string;
    salesCloseAt: string;
    // Optional searchable metadata — backend accepts nulls.
    primaryArtist?: string;
    venueName?: string;
    venueCity?: string;
    shortDescription?: string;
    fullDescription?: string;
    category?: string;
    genre?: string;
  }) =>
    api.post<ApiResponse<Event>>('/api/tickets/events', body)
       .then((r: AxiosResponse<ApiResponse<Event>>) => normaliseEvent(r.data.data)),

  /**
   * Paged list of AVAILABLE tickets for an event with effective (surge-adjusted)
   * prices already applied. Single backend pricing-service call per page,
   * regardless of how many tickets the page contains.
   *
   * <p>Powers the event-detail page reached from a search-result click. See
   * README → "Search subsystem" + Phase 3b in the integration plan.
   */
  listAvailableForEvent: (eventId: string, params?: { page?: number; size?: number }) =>
    api
      .get<ApiResponse<AvailableTicketsPage>>(
        `/api/tickets/events/${eventId}/tickets`,
        { params },
      )
      .then((r: AxiosResponse<ApiResponse<AvailableTicketsPage>>) => r.data.data),

  openEvent: (eventId: string) =>
    api.patch<ApiResponse<Event>>(`/api/tickets/events/${eventId}/open`)
       .then((r: AxiosResponse<ApiResponse<Event>>) => normaliseEvent(r.data.data)),

  closeEvent: (eventId: string) =>
    api.patch<ApiResponse<Event>>(`/api/tickets/events/${eventId}/close`)
       .then((r: AxiosResponse<ApiResponse<Event>>) => normaliseEvent(r.data.data)),

  cancelEvent: (eventId: string) =>
    api.patch<ApiResponse<Event>>(`/api/tickets/events/${eventId}/cancel`)
       .then((r: AxiosResponse<ApiResponse<Event>>) => normaliseEvent(r.data.data)),

  // ── Tickets ─────────────────────────────────────────────────────────────────
  listAvailable: () =>
    api.get<ApiResponse<Ticket[]>>('/api/tickets/available').then((r: AxiosResponse<ApiResponse<Ticket[]>>) => r.data.data),

  listAll: () =>
    api.get<ApiResponse<Ticket[]>>('/api/tickets').then((r: AxiosResponse<ApiResponse<Ticket[]>>) => r.data.data),

  /** Fetch all tickets for a single event — uses the required ?eventId param. */
  listByEvent: (eventId: string) =>
    api.get<ApiResponse<Ticket[]>>('/api/tickets', { params: { eventId } })
      .then((r: AxiosResponse<ApiResponse<Ticket[]>>) => r.data.data),

  getTicket: (id: string) =>
    api.get<ApiResponse<Ticket>>(`/api/tickets/${id}`).then((r: AxiosResponse<ApiResponse<Ticket>>) => r.data.data),

  createTicket: (body: { eventId: string; section?: string; row?: string; seat: string; facePrice: number }) =>
    api.post<ApiResponse<Ticket>>('/api/tickets', body).then((r: AxiosResponse<ApiResponse<Ticket>>) => r.data.data),

  updateTicket: (id: string, body: Partial<{ section: string; row: string; seat: string; facePrice: number }>) =>
    api.put<ApiResponse<Ticket>>(`/api/tickets/${id}`, body).then((r: AxiosResponse<ApiResponse<Ticket>>) => r.data.data),

  deleteTicket: (id: string) =>
    api.delete(`/api/tickets/${id}`),

  /**
   * Bulk-create tickets from a seat-range definition.
   * The backend expands rowStart..rowEnd × seatStart..seatEnd and skips
   * any seats that already exist. Returns only the newly created tickets.
   */
  createTicketsBatch: (body: {
    eventId: string;
    eventName: string;
    section?: string;
    rowStart?: string;
    rowEnd?: string;
    seatStart: number;
    seatEnd: number;
    facePrice: number;
  }) =>
    api.post<ApiResponse<Ticket[]>>('/api/tickets/batch', body)
      .then((r: AxiosResponse<ApiResponse<Ticket[]>>) => r.data.data),
};
