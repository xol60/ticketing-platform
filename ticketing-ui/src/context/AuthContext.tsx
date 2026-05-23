import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi } from '../api/auth';
import type { User, Role } from '../types';

interface AuthState {
  user: User | null;
  loading: boolean;
  /**
   * Log in with either a username OR an email address — the backend resolves
   * either against {@code users.username} / {@code users.email}.
   */
  login: (emailOrUsername: string, password: string) => Promise<void>;
  register: (email: string, username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  isAuthenticated: boolean;
  hasRole: (role: Role) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

/** Shared helper — backend returns a flat AuthResponse, but we want the full
 *  {@link User} in context. So after every token-receiving call we hit /me. */
async function persistTokensAndLoadUser(accessToken: string, refreshToken: string): Promise<User> {
  localStorage.setItem('accessToken', accessToken);
  localStorage.setItem('refreshToken', refreshToken);
  return await authApi.me();
}

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

  const login = async (emailOrUsername: string, password: string) => {
    const data = await authApi.login({ emailOrUsername, password });
    const u = await persistTokensAndLoadUser(data.accessToken, data.refreshToken);
    setUser(u);
  };

  // Register is USER-only — the backend ignores any client-supplied role.
  // ADMIN / EVENT_OWNER promotion happens through admin endpoints, not signup.
  const register = async (email: string, username: string, password: string) => {
    const data = await authApi.register({ email, username, password });
    const u = await persistTokensAndLoadUser(data.accessToken, data.refreshToken);
    setUser(u);
  };

  const logout = async () => {
    try { await authApi.logout(); } catch { /* ignore — token cleared either way */ }
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
