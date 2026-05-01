import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { NotificationBell } from './NotificationBell';

export function Navbar() {
  const { user, isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const navLink = 'text-sm font-medium text-gray-600 hover:text-blue-600 transition-colors px-1 py-0.5';
  const activeNavLink = 'text-sm font-medium text-blue-600 px-1 py-0.5 border-b-2 border-blue-600';

  return (
    <nav className="bg-white border-b border-gray-100 sticky top-0 z-30 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">

          {/* Logo */}
          <Link to="/" className="flex items-center gap-2 font-bold text-blue-600 text-lg">
            <span className="text-2xl">🎟️</span>
            <span>TicketHub</span>
          </Link>

          {/* Desktop nav links */}
          <div className="hidden md:flex items-center gap-6">
            <NavLink to="/" end className={({ isActive }: { isActive: boolean }) => isActive ? activeNavLink : navLink}>
              Events
            </NavLink>
            <NavLink to="/secondary" className={({ isActive }: { isActive: boolean }) => isActive ? activeNavLink : navLink}>
              Resale Market
            </NavLink>
            {isAuthenticated && (
              <NavLink to="/orders" className={({ isActive }: { isActive: boolean }) => isActive ? activeNavLink : navLink}>
                My Orders
              </NavLink>
            )}
            {user?.role === 'EVENT_OWNER' && (
              <NavLink to="/creator" className={({ isActive }: { isActive: boolean }) => isActive ? activeNavLink : navLink}>
                My Events
              </NavLink>
            )}
          </div>

          {/* Right side */}
          <div className="flex items-center gap-3">
            <NotificationBell />

            {isAuthenticated ? (
              <div className="relative">
                <button
                  onClick={() => setMenuOpen((v) => !v)}
                  className="flex items-center gap-2 px-3 py-1.5 rounded-full border border-gray-200 hover:bg-gray-50 transition-colors text-sm text-gray-700"
                >
                  <span className="w-7 h-7 bg-blue-600 text-white rounded-full flex items-center justify-center text-xs font-bold uppercase">
                    {user?.username?.charAt(0) ?? '?'}
                  </span>
                  <span className="hidden sm:inline max-w-24 truncate">{user?.username}</span>
                  <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                  </svg>
                </button>
                {menuOpen && (
                  <>
                    <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
                    <div className="absolute right-0 mt-2 w-48 bg-white rounded-xl shadow-lg border border-gray-100 z-20 overflow-hidden">
                      <Link
                        to="/profile"
                        onClick={() => setMenuOpen(false)}
                        className="block px-4 py-2.5 text-sm text-gray-700 hover:bg-gray-50 transition-colors"
                      >
                        My Profile
                      </Link>
                      {user?.role === 'EVENT_OWNER' && (
                        <Link
                          to="/creator"
                          onClick={() => setMenuOpen(false)}
                          className="block px-4 py-2.5 text-sm text-gray-700 hover:bg-gray-50 transition-colors"
                        >
                          Creator Dashboard
                        </Link>
                      )}
                      <hr className="my-1 border-gray-100" />
                      <button
                        onClick={handleLogout}
                        className="w-full text-left px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 transition-colors"
                      >
                        Log out
                      </button>
                    </div>
                  </>
                )}
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <Link to="/login" className="text-sm font-medium text-gray-600 hover:text-blue-600 transition-colors px-3 py-1.5">
                  Sign in
                </Link>
                <Link to="/register" className="text-sm font-medium bg-blue-600 hover:bg-blue-700 text-white px-4 py-1.5 rounded-lg transition-colors">
                  Sign up
                </Link>
              </div>
            )}
          </div>
        </div>
      </div>
    </nav>
  );
}
