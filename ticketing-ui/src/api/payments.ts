import type { AxiosResponse } from 'axios';
import api from './client';
import type { Payment, ApiResponse } from '../types';

export const paymentsApi = {
  getByOrder: (orderId: string) =>
    api.get<ApiResponse<Payment>>(`/api/payments/${orderId}`).then((r: AxiosResponse<ApiResponse<Payment>>) => r.data.data),

  getStatus: (orderId: string) =>
    api.get<ApiResponse<{ status: string }>>(`/api/payments/${orderId}/status`).then((r: AxiosResponse<ApiResponse<{ status: string }>>) => r.data.data),
};
