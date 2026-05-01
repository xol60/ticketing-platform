import type { AxiosResponse } from 'axios';
import api from './client';
import type { Order, ApiResponse } from '../types';

export const ordersApi = {
  create: (body: { ticketId: string; userPrice: number }) =>
    api.post<ApiResponse<Order>>('/api/orders', body).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),

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
