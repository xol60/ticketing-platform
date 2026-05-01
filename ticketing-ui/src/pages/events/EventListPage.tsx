import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { EventStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import type { Event } from '../../types';

function EventCard({ event }: { event: Event }) {
  const isOpen = event.status === 'OPEN';
  const eventDate = new Date(event.eventDate);
  const salesClose = new Date(event.salesCloseAt);
  const daysLeft = Math.max(0, Math.ceil((salesClose.getTime() - Date.now()) / 86_400_000));

  return (
    <Link to={`/events/${event.id}`} className="group block">
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden hover:shadow-md hover:border-blue-100 transition-all duration-200">
        {/* Color bar by status */}
        <div className={`h-1.5 ${isOpen ? 'bg-gradient-to-r from-blue-500 to-indigo-500' : 'bg-gray-200'}`} />
        <div className="p-5">
          <div className="flex items-start justify-between gap-3">
            <h3 className="text-base font-semibold text-gray-900 group-hover:text-blue-600 transition-colors line-clamp-2">
              {event.name}
            </h3>
            <EventStatusBadge status={event.status} />
          </div>

          <div className="mt-3 flex flex-col gap-1.5 text-sm text-gray-500">
            <div className="flex items-center gap-2">
              <span>📅</span>
              <span>{eventDate.toLocaleDateString('en-US', { dateStyle: 'medium' })}</span>
            </div>
            <div className="flex items-center gap-2">
              <span>🕐</span>
              <span>{eventDate.toLocaleTimeString('en-US', { timeStyle: 'short' })}</span>
            </div>
            {isOpen && daysLeft > 0 && (
              <div className="flex items-center gap-2 text-blue-600 font-medium">
                <span>⏳</span>
                <span>{daysLeft} day{daysLeft !== 1 ? 's' : ''} left to buy</span>
              </div>
            )}
          </div>

          <div className="mt-4 pt-3 border-t border-gray-50 flex items-center justify-between">
            <span className="text-xs text-gray-400">
              Sales close {salesClose.toLocaleDateString('en-US', { dateStyle: 'short' })}
            </span>
            <span className={`text-sm font-medium ${isOpen ? 'text-blue-600' : 'text-gray-400'}`}>
              {isOpen ? 'View tickets →' : 'Unavailable'}
            </span>
          </div>
        </div>
      </div>
    </Link>
  );
}

export function EventListPage() {
  const [search, setSearch] = useState('');
  const { data: events = [], isLoading, isError } = useQuery({
    queryKey: ['events'],
    queryFn: ticketsApi.listEvents,
  });

  const filtered = events.filter((e) =>
    e.name.toLowerCase().includes(search.toLowerCase())
  );

  const openEvents   = filtered.filter((e) => e.status === 'OPEN');
  const otherEvents  = filtered.filter((e) => e.status !== 'OPEN');

  return (
    <div className="flex flex-col gap-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Events</h1>
          <p className="text-gray-500 mt-1">Browse and buy tickets for upcoming events</p>
        </div>
        <input
          type="search"
          placeholder="Search events…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full sm:w-64 px-4 py-2 rounded-xl border border-gray-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>

      {isLoading && <PageSpinner />}
      {isError && (
        <div className="text-center py-16 text-red-500">Failed to load events. Please try again.</div>
      )}

      {!isLoading && !isError && (
        <>
          {/* Open events */}
          {openEvents.length > 0 && (
            <section>
              <h2 className="text-lg font-semibold text-gray-800 mb-4 flex items-center gap-2">
                <span className="w-2 h-2 bg-green-500 rounded-full inline-block"></span>
                On sale now
              </h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {openEvents.map((e) => <EventCard key={e.id} event={e} />)}
              </div>
            </section>
          )}

          {/* Other events */}
          {otherEvents.length > 0 && (
            <section>
              <h2 className="text-lg font-semibold text-gray-800 mb-4 text-gray-400">Other events</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {otherEvents.map((e) => <EventCard key={e.id} event={e} />)}
              </div>
            </section>
          )}

          {filtered.length === 0 && (
            <div className="text-center py-20 text-gray-400">
              <p className="text-5xl mb-4">🎭</p>
              <p className="text-lg font-medium">No events found</p>
              <p className="text-sm mt-1">Try a different search term</p>
            </div>
          )}
        </>
      )}
    </div>
  );
}
