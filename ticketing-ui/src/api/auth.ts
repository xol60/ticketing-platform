import type { AxiosResponse } from 'axios';
import api from './client';
import type { AuthResponse, User, ApiResponse } from '../types';

/**
 * Auth client.
 *
 * Backend lives behind nginx → api-gateway → auth-service. All paths therefore
 * start with `/api/auth/...` (the SPA is on the same origin so axios baseURL='/'
 * is fine — anything else routes through nginx to the SPA bundle and returns
 * HTML, which is what caused the original "login does nothing" bug).
 *
 * Login accepts either a username or an email in {@code emailOrUsername} — the
 * backend resolves both. The LoginPage uses a plain text input (no HTML5 email
 * validation) so users seeded without an email-shaped username can still log in.
 */
export const authApi = {
  register: (body: { email: string; username: string; password: string }) =>
    api.post<ApiResponse<AuthResponse>>('/api/auth/register', body)
       .then((r: AxiosResponse<ApiResponse<AuthResponse>>) => r.data.data),

  login: (body: { emailOrUsername: string; password: string }) =>
    api.post<ApiResponse<AuthResponse>>('/api/auth/login', body)
       .then((r: AxiosResponse<ApiResponse<AuthResponse>>) => r.data.data),

  refresh: (refreshToken: string) =>
    api.post<ApiResponse<AuthResponse>>('/api/auth/refresh', { refreshToken })
       .then((r: AxiosResponse<ApiResponse<AuthResponse>>) => r.data.data),

  logout:    () => api.post('/api/auth/logout'),
  logoutAll: () => api.post('/api/auth/logout-all'),

  me: () => api.get<ApiResponse<User>>('/api/auth/me')
              .then((r: AxiosResponse<ApiResponse<User>>) => r.data.data),
};
