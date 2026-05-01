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

  getTicket: (id: string) =>
    api.get<ApiResponse<Ticket>>(`/api/tickets/${id}`).then((r: AxiosResponse<ApiResponse<Ticket>>) => r.data.data),

  createTicket: (body: { eventId: string; section?: string; row?: string; seat: string; facePrice: number }) =>
    api.post<ApiResponse<Ticket>>('/api/tickets', body).then((r: AxiosResponse<ApiResponse<Ticket>>) => r.data.data),

  updateTicket: (id: string, body: Partial<{ section: string; row: string; seat: string; facePrice: number }>) =>
    api.put<ApiResponse<Ticket>>(`/api/tickets/${id}`, body).then((r: AxiosResponse<ApiResponse<Ticket>>) => r.data.data),

  deleteTicket: (id: string) =>
    api.delete(`/api/tickets/${id}`),
};
