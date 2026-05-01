import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi } from '../api/auth';
import type { User, Role } from '../types';

interface AuthState {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, username: string, password: string, role?: Role) => Promise<void>;
  logout: () => Promise<void>;
  isAuthenticated: boolean;
  hasRole: (role: Role) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  // ── Restore session on mount ─────────────────────────────────────────────
  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) { setLoading(false); return; }
    authApi.me()
      .then(setUser)
      .catch(() => localStorage.clear())
      .finally(() => setLoading(false));
  }, []);

  const login = async (email: string, password: string) => {
    const data = await authApi.login({ email, password });
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
    setUser(data.user);
  };

  const register = async (email: string, username: string, password: string, role: Role = 'USER') => {
    const data = await authApi.register({ email, username, password, role });
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
    setUser(data.user);
  };

  const logout = async () => {
    try { await authApi.logout(); } catch { /* ignore */ }
    localStorage.clear();
    setUser(null);
  };

  const hasRole = (role: Role) => user?.role === role;

  return (
    <AuthContext.Provider value={{
      user, loading, login, register, logout,
      isAuthenticated: !!user,
      hasRole,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
