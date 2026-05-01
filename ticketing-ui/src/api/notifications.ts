import type { AxiosResponse } from 'axios';
import api from './client';
import type { NotificationLog, ApiResponse } from '../types';

export const notificationsApi = {
  list: () =>
    api.get<ApiResponse<NotificationLog[]>>('/api/notifications').then((r: AxiosResponse<ApiResponse<NotificationLog[]>>) => r.data.data),
};
