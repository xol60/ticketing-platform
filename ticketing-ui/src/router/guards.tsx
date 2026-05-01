import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import type { Role } from '../types';

/** Redirects unauthenticated users to /login */
export function PrivateRoute() {
  const { isAuthenticated, loading } = useAuth();
  if (loading) return <div className="flex items-center justify-center h-screen text-gray-500">Loading…</div>;
  return isAuthenticated ? <Outlet /> : <Navigate to="/login" replace />;
}

/** Redirects users who don't have the required role */
export function RoleRoute({ role }: { role: Role }) {
  const { isAuthenticated, hasRole, loading } = useAuth();
  if (loading) return <div className="flex items-center justify-center h-screen text-gray-500">Loading…</div>;
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (!hasRole(role)) return <Navigate to="/" replace />;
  return <Outlet />;
}
