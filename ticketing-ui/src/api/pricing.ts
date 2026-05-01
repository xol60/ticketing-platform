import type { AxiosResponse } from 'axios';
import api from './client';
import type { PriceRule, ApiResponse } from '../types';

export const pricingApi = {
  getRule: (eventId: string) =>
    api.get<ApiResponse<PriceRule>>(`/api/pricing/rules/${eventId}`).then((r: AxiosResponse<ApiResponse<PriceRule>>) => r.data.data),

  createRule: (body: { eventId: string; maxSurge: number; surgeMultiplier?: number }) =>
    api.post<ApiResponse<PriceRule>>('/api/pricing/rules', body).then((r: AxiosResponse<ApiResponse<PriceRule>>) => r.data.data),

  updateRule: (eventId: string, body: Partial<{ maxSurge: number; surgeMultiplier: number }>) =>
    api.put<ApiResponse<PriceRule>>(`/api/pricing/rules/${eventId}`, body).then((r: AxiosResponse<ApiResponse<PriceRule>>) => r.data.data),

  getTicketPrice: (ticketId: string) =>
    api.get<ApiResponse<{ price: number }>>(`/api/pricing/tickets/${ticketId}/price`).then((r: AxiosResponse<ApiResponse<{ price: number }>>) => r.data.data),
};
