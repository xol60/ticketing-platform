import type { AxiosResponse } from 'axios';
import api from './client';
import { newIdempotencyKey, idempotencyHeaders } from '../lib/idempotency';
import type { Listing, Order, ApiResponse } from '../types';

export const secondaryApi = {
  listAll: () =>
    api.get<ApiResponse<Listing[]>>('/api/secondary/listings').then((r: AxiosResponse<ApiResponse<Listing[]>>) => r.data.data),

  getListing: (id: string) =>
    api.get<ApiResponse<Listing>>(`/api/secondary/listings/${id}`).then((r: AxiosResponse<ApiResponse<Listing>>) => r.data.data),

  /**
   * Create a resale listing. Carries an idempotency key for the same reason
   * as {@code ordersApi.create} — a double-click on "List my ticket" must
   * not produce two listings against the same ticket. Server-side
   * IdempotencyFilter on /api/secondary/listings handles the dedup.
   */
  createListing: (
    body: { ticketId: string; eventId: string; askPrice: number },
    idempotencyKey: string = newIdempotencyKey(),
  ) =>
    api.post<ApiResponse<Listing>>('/api/secondary/listings', body, {
      headers: idempotencyHeaders(idempotencyKey),
    }).then((r: AxiosResponse<ApiResponse<Listing>>) => r.data.data),

  cancelListing: (id: string) =>
    api.delete(`/api/secondary/listings/${id}`),

  purchase: (id: string) =>
    api.post<ApiResponse<Order>>(`/api/secondary/listings/${id}/purchase`).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),
};
