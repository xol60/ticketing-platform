import type { AxiosResponse } from 'axios';
import api from './client';
import type { AuthResponse, User, ApiResponse } from '../types';

export const authApi = {
  register: (body: { email: string; username: string; password: string; role?: string }) =>
    api.post<ApiResponse<AuthResponse>>('/auth/register', body).then((r: AxiosResponse<ApiResponse<AuthResponse>>) => r.data.data),

  login: (body: { email: string; password: string }) =>
    api.post<ApiResponse<AuthResponse>>('/auth/login', body).then((r: AxiosResponse<ApiResponse<AuthResponse>>) => r.data.data),

  refresh: (refreshToken: string) =>
    api.post<ApiResponse<AuthResponse>>('/auth/refresh', { refreshToken }).then((r: AxiosResponse<ApiResponse<AuthResponse>>) => r.data.data),

  logout: () => api.post('/auth/logout'),

  logoutAll: () => api.post('/auth/logout-all'),

  me: () => api.get<ApiResponse<User>>('/auth/me').then((r: AxiosResponse<ApiResponse<User>>) => r.data.data),
};
