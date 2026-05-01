import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { notificationsApi } from '../../api/notifications';
import { useAuth } from '../../context/AuthContext';
import type { NotificationLog } from '../../types';

export function NotificationBell() {
  const { isAuthenticated } = useAuth();
  const [open, setOpen] = useState(false);

  const { data: notifications = [] } = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.list,
    enabled: isAuthenticated,
    refetchInterval: 30_000,
  });

  const unread = notifications.filter((n: NotificationLog) => !n.read).length;

  if (!isAuthenticated) return null;

  return (
    <div className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className="relative p-2 rounded-full text-gray-500 hover:text-gray-700 hover:bg-gray-100 transition-colors"
      >
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6 6 0 10-12 0v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
        </svg>
        {unread > 0 && (
          <span className="absolute top-0.5 right-0.5 bg-red-500 text-white text-[10px] font-bold rounded-full w-4 h-4 flex items-center justify-center">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute right-0 mt-2 w-80 bg-white rounded-xl shadow-lg border border-gray-100 z-20 overflow-hidden">
            <div className="px-4 py-3 border-b border-gray-100">
              <h3 className="text-sm font-semibold text-gray-900">Notifications</h3>
            </div>
            <div className="max-h-80 overflow-y-auto divide-y divide-gray-50">
              {notifications.length === 0 ? (
                <p className="px-4 py-6 text-sm text-gray-400 text-center">No notifications yet</p>
              ) : (
                notifications.slice(0, 10).map((n: NotificationLog) => (
                  <div key={n.id} className={`px-4 py-3 hover:bg-gray-50 transition-colors ${!n.read ? 'bg-blue-50/40' : ''}`}>
                    <p className="text-sm font-medium text-gray-800">{n.subject}</p>
                    <p className="text-xs text-gray-500 mt-0.5 line-clamp-2">{n.body}</p>
                    <p className="text-[11px] text-gray-400 mt-1">{new Date(n.sentAt).toLocaleString()}</p>
                  </div>
                ))
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
