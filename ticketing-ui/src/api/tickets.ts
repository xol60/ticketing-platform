import type { AxiosResponse } from 'axios';
import api from './client';
import type { Event, Ticket, ApiResponse } from '../types';

export const ticketsApi = {
  // ── Events ──────────────────────────────────────────────────────────────────
  listEvents: () =>
    api.get<ApiResponse<Event[]>>('/api/tickets/events').then((r: AxiosResponse<ApiResponse<Event[]>>) => r.data.data),

  createEvent: (body: { name: string; eventDate: string; salesOpenAt: string; salesCloseAt: string }) =>
    api.post<ApiResponse<Event>>('/api/tickets/events', body).then((r: AxiosResponse<ApiResponse<Event>>) => r.data.data),

  openEvent: (eventId: string) =>
    api.patch<ApiResponse<Event>>(`/api/tickets/events/${eventId}/open`).then((r: AxiosResponse<ApiResponse<Event>>) => r.data.data),

  closeEvent: (eventId: string) =>
    api.patch<ApiResponse<Event>>(`/api/tickets/events/${eventId}/close`).then((r: AxiosResponse<ApiResponse<Event>>) => r.data.data),

  cancelEvent: (eventId: string) =>
    api.patch<ApiResponse<Event>>(`/api/tickets/events/${eventId}/cancel`).then((r: AxiosResponse<ApiResponse<Event>>) => r.data.data),

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
