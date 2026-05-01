import type { AxiosResponse } from 'axios';
import api from './client';
import type { Listing, Order, ApiResponse } from '../types';

export const secondaryApi = {
  listAll: () =>
    api.get<ApiResponse<Listing[]>>('/api/secondary/listings').then((r: AxiosResponse<ApiResponse<Listing[]>>) => r.data.data),

  getListing: (id: string) =>
    api.get<ApiResponse<Listing>>(`/api/secondary/listings/${id}`).then((r: AxiosResponse<ApiResponse<Listing>>) => r.data.data),

  createListing: (body: { ticketId: string; eventId: string; askPrice: number }) =>
    api.post<ApiResponse<Listing>>('/api/secondary/listings', body).then((r: AxiosResponse<ApiResponse<Listing>>) => r.data.data),

  cancelListing: (id: string) =>
    api.delete(`/api/secondary/listings/${id}`),

  purchase: (id: string) =>
    api.post<ApiResponse<Order>>(`/api/secondary/listings/${id}/purchase`).then((r: AxiosResponse<ApiResponse<Order>>) => r.data.data),
};
