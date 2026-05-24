import type { AxiosResponse } from 'axios';
import api from './client';
import { newIdempotencyKey, idempotencyHeaders } from '../lib/idempotency';
import type { Order, ApiResponse } from '../types';

export const ordersApi = {
  /**
   * Create an order. The {@code idempotencyKey} parameter is optional:
   * if omitted, a fresh UUIDv4 is generated per call. Callers that retry
   * the SAME logical intent (e.g. a React Query mutation that auto-retries
   * after a 502) should pass the same key both times so the server's
   * Stripe-style dedup catches the duplicate.
   *
   * <p>The server side dedups on {@code (X-User-Id, Idempotency-Key)}, so
   * the key alone is harmless if intercepted — it can't be used by another
   * user. See common-lib's {@code IdempotencyFilter}.
   */
  create: (
    body: { ticketId: string; requestedPrice: number },
    idempotencyKey: string = newIdempotencyKey(),
  ) =>
    api.post<ApiResponse<Order>>('/api/orders', body, {
      headers: idempotencyHeaders(idempotencyKey),
    }).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),

  getOrder: (id: string) =>
    api.get<ApiResponse<Order>>(`/api/orders/${id}`).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),

  listMyOrders: () =>
    api.get<ApiResponse<Order[]>>('/api/orders').then((r: AxiosResponse<ApiResponse<Order[]>>) => r.data.data),

  confirmPrice: (id: string) =>
    api.post<ApiResponse<Order>>(`/api/orders/${id}/confirm-price`).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),

  cancelPrice: (id: string) =>
    api.post<ApiResponse<Order>>(`/api/orders/${id}/cancel-price`).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),

  /** Returns an EventSource for real-time order status updates */
  streamOrder: (id: string): EventSource => {
    const token = localStorage.getItem('accessToken');
    return new EventSource(`/api/orders/${id}/stream?token=${token}`);
  },
};
