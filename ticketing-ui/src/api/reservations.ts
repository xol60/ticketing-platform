import type { AxiosResponse } from 'axios';
import api from './client';
import type { Reservation, WaitlistPosition, ApiResponse } from '../types';

export const reservationsApi = {
  join: (ticketId: string) =>
    api.post<ApiResponse<Reservation>>('/api/reservations', { ticketId }).then((r: AxiosResponse<ApiResponse<Reservation>>) => r.data.data),

  leave: (reservationId: string) =>
    api.delete(`/api/reservations/${reservationId}`),

  getPosition: (ticketId: string) =>
    api.get<ApiResponse<WaitlistPosition>>(`/api/reservations/${ticketId}/position`).then((r: AxiosResponse<ApiResponse<WaitlistPosition>>) => r.data.data),
};
