import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { ordersApi } from '../../api/orders';
import { EventStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import { Button } from '../../components/ui/Button';
import { useAuth } from '../../context/AuthContext';
import { money, multiplier } from '../../lib/format';
import type { AvailableTicketSummary, Order } from '../../types';

/**
 * An order is "blocking" the Buy button for a ticket if it's in any
 * non-terminal or already-successful state — anything that means "the
 * user has already committed (or is committing) to this ticket."
 *
 * <p>FAILED and CANCELLED orders do NOT block — the user is free to try
 * again with a fresh order.
 */
const BLOCKING_ORDER_STATUSES = new Set<Order['status']>([
  'PENDING',
  'PRICE_CHANGED',
  'CONFIRMED',
]);

const PAGE_SIZE = 50;

/**
 * Single row in the available-tickets table.
 *
 * <p>Reads from the Phase 3b page projection — the server has already applied
 * the per-event surge multiplier to {@code effectivePrice}, so the UI does
 * not need to make N pricing calls (one per ticket). The "(surge X.Xx)"
 * indicator on the header takes care of explaining what the orange price
 * means.
 */
function AvailableTicketRow({
  ticket,
  surgeMultiplier,
  eventOpen,
  /** Order this user already has for this exact ticket (if any). */
  existingOrder,
}: {
  ticket: AvailableTicketSummary;
  surgeMultiplier: number;
  eventOpen: boolean;
  existingOrder?: Order;
}) {
  const { isAuthenticated } = useAuth();
  const hasSurge = surgeMultiplier > 1.0;
  // Disable the Buy button if the logged-in user already has a non-terminal
  // (or already-confirmed) order for this exact ticket. Stops the most
  // common panic-click case — the user clicks Buy, watches the saga work,
  // gets impatient and clicks Buy again on the same ticket from another tab.
  const blocked = existingOrder != null;

  return (
    <div className="flex items-center justify-between px-4 py-3 rounded-xl border border-gray-100 bg-white hover:border-blue-100 hover:bg-blue-50/20 transition-colors">
      <div className="text-sm">
        <p className="font-medium text-gray-900">
          {ticket.section ? `${ticket.section} · ` : ''}Row {ticket.row ?? '—'} · Seat {ticket.seat}
        </p>
        <p className="text-gray-400 text-xs mt-0.5">ID: {ticket.id.slice(0, 8)}…</p>
      </div>
      <div className="flex items-center gap-4">
        <div className="text-right">
          <p className={`font-bold text-base ${hasSurge ? 'text-orange-600' : 'text-gray-900'}`}>
            {money(ticket.effectivePrice)}
          </p>
          {hasSurge && (
            <p className="text-xs text-gray-400 line-through">
              {money(ticket.facePrice)}
            </p>
          )}
        </div>
        {eventOpen && (
          !isAuthenticated
            ? <Link to="/login" state={{ from: `/tickets/${ticket.id}` }}>
                <Button size="sm" variant="secondary">Sign in to buy</Button>
              </Link>
            : blocked
              ? <Link to={`/orders/${existingOrder!.id}`}>
                  {/* Disabled-looking but still a link to the user's
                      existing order — gives them a path forward instead of
                      a dead end. */}
                  <Button size="sm" variant="secondary"
                          title={`You already have an order for this ticket (${existingOrder!.status})`}>
                    {existingOrder!.status === 'CONFIRMED' ? '✓ Owned' : '⏳ In progress'}
                  </Button>
                </Link>
              : <Link to={`/tickets/${ticket.id}`}>
                  <Button size="sm">Buy</Button>
                </Link>
        )}
      </div>
    </div>
  );
}

/** Pill-style row of event metadata under the title. */
function EventMetadata({
  primaryArtist,
  venueName,
  venueCity,
  category,
  genre,
}: {
  primaryArtist?: string;
  venueName?: string;
  venueCity?: string;
  category?: string;
  genre?: string;
}) {
  const meta: string[] = [];
  if (primaryArtist) meta.push(primaryArtist);
  if (venueName)     meta.push(venueName);
  if (venueCity)     meta.push(venueCity);

  return (
    <>
      {meta.length > 0 && (
        <p className="text-sm text-gray-600 mt-1">{meta.join(' · ')}</p>
      )}
      {(category || genre) && (
        <div className="flex flex-wrap gap-1.5 mt-2">
          {category && (
            <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-indigo-50 text-indigo-700">
              {category}
            </span>
          )}
          {genre && (
            <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-purple-50 text-purple-700">
              {genre}
            </span>
          )}
        </div>
      )}
    </>
  );
}

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const [page, setPage] = useState(0);
  const { isAuthenticated } = useAuth();

  // Event metadata — still served by the existing list endpoint. (We could
  // switch this to a single /api/tickets/events/{id} call once that endpoint
  // exists publicly; for now it's a list lookup.)
  const { data: events = [], isLoading: eventsLoading } = useQuery({
    queryKey: ['events'],
    queryFn:  ticketsApi.listEvents,
  });

  // User's own orders — only fetched when logged in. Used to disable the
  // Buy button on tickets the user already has a live or confirmed order
  // for. Short staleTime so the disable flips back to "Buy" quickly if the
  // user cancels a pending order.
  const { data: myOrders = [] } = useQuery({
    queryKey: ['my-orders'],
    queryFn:  ordersApi.listMyOrders,
    enabled:  isAuthenticated,
    staleTime: 15_000,
  });

  // Map ticketId → blocking order (if any) so the per-row check is O(1).
  // We only consider orders for ANY ticket on this event, but the index
  // is by ticketId so the row component just looks itself up.
  const blockingOrderByTicket = useMemo(() => {
    const m = new Map<string, Order>();
    for (const o of myOrders) {
      if (BLOCKING_ORDER_STATUSES.has(o.status)) {
        // If a user somehow has multiple non-terminal orders for the same
        // ticket, prefer the most recent one (already-CONFIRMED beats PENDING).
        const prior = m.get(o.ticketId);
        if (!prior || prior.status !== 'CONFIRMED') m.set(o.ticketId, o);
      }
    }
    return m;
  }, [myOrders]);

  // Available tickets — Phase 3b paged endpoint.
  // One pricing-service call PER PAGE (Caffeine-cached server-side for 30s),
  // not per ticket. This is the surge multiplier visible at "(surge 1.5x)".
  const { data: ticketsPage, isLoading: ticketsLoading } = useQuery({
    queryKey: ['available-tickets', eventId, page],
    queryFn:  () => ticketsApi.listAvailableForEvent(eventId!, { page, size: PAGE_SIZE }),
    enabled:  !!eventId,
    placeholderData: keepPreviousData,
    // Refetch every 15s so newly released tickets show up and surge changes
    // propagate. The cache absorbs the actual upstream pricing call.
    refetchInterval: 15_000,
  });

  const event = events.find((e) => e.id === eventId);
  const isLoading = eventsLoading || (ticketsLoading && !ticketsPage);

  if (isLoading) return <PageSpinner />;
  if (!event) return (
    <div className="text-center py-20 text-gray-400">
      <p className="text-5xl mb-4">🔍</p>
      <p className="text-lg font-medium">Event not found</p>
      <Link to="/" className="text-blue-600 text-sm hover:underline mt-2 inline-block">Back to events</Link>
    </div>
  );

  const eventOpen = event.status === 'OPEN';
  const surge = ticketsPage?.surgeMultiplier ?? 1.0;
  const totalAvailable = ticketsPage?.totalAvailable ?? 0;
  const totalPages = ticketsPage ? Math.ceil(totalAvailable / PAGE_SIZE) : 0;
  const tickets = ticketsPage?.tickets ?? [];

  return (
    <div className="flex flex-col gap-6">
      {/* Breadcrumb */}
      <nav className="text-sm text-gray-400 flex items-center gap-2">
        <Link to="/" className="hover:text-blue-600 transition-colors">Events</Link>
        <span>/</span>
        <span className="text-gray-700 font-medium">{event.name}</span>
      </nav>

      {/* Event header */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 flex flex-col sm:flex-row gap-4 items-start justify-between">
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-3 flex-wrap">
            <h1 className="text-2xl font-bold text-gray-900">{event.name}</h1>
            <EventStatusBadge status={event.status} />
          </div>

          <EventMetadata
            primaryArtist={event.primaryArtist}
            venueName={event.venueName}
            venueCity={event.venueCity}
            category={event.category}
            genre={event.genre}
          />

          {event.shortDescription && (
            <p className="text-sm text-gray-600 mt-2 max-w-prose">{event.shortDescription}</p>
          )}

          <div className="flex flex-wrap gap-4 text-sm text-gray-500 mt-2">
            <span className="flex items-center gap-1">
              📅 {new Date(event.eventDate).toLocaleDateString('en-US', { dateStyle: 'long' })}
            </span>
            <span className="flex items-center gap-1">
              🕐 {new Date(event.eventDate).toLocaleTimeString('en-US', { timeStyle: 'short' })}
            </span>
          </div>
          <p className="text-sm text-gray-400">
            Sales: {new Date(event.salesOpenAt).toLocaleDateString()} – {new Date(event.salesCloseAt).toLocaleDateString()}
          </p>
        </div>

        <div className="text-right shrink-0 flex flex-col items-end gap-2">
          <div>
            <p className="text-2xl font-bold text-blue-600">{totalAvailable}</p>
            <p className="text-sm text-gray-400">available</p>
          </div>
          {surge > 1.0 && (
            <span
              className="px-3 py-1 rounded-full text-xs font-semibold bg-orange-50 text-orange-700"
              title="Effective prices reflect this surge multiplier"
            >
              ⚡ Surge {multiplier(surge)}
            </span>
          )}
        </div>
      </div>

      {/* Tickets */}
      <div className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold text-gray-800 flex items-center gap-2">
          Available tickets
          <span className="text-sm font-normal text-gray-400">
            (showing {tickets.length} of {totalAvailable})
          </span>
        </h2>

        {totalAvailable === 0 ? (
          <div className="text-center py-16 text-gray-400 bg-white rounded-2xl border border-gray-100">
            <p className="text-4xl mb-3">🎫</p>
            <p>No tickets currently available for this event.</p>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {tickets.map((t) => (
              <AvailableTicketRow
                key={t.id}
                ticket={t}
                surgeMultiplier={surge}
                eventOpen={eventOpen}
                existingOrder={blockingOrderByTicket.get(t.id)}
              />
            ))}
          </div>
        )}

        {totalPages > 1 && (
          <div className="flex items-center justify-center gap-2 pt-2">
            <button
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-3 py-1.5 rounded-lg border border-gray-200 text-sm disabled:opacity-40"
            >
              ← Prev
            </button>
            <span className="text-sm text-gray-500">
              Page {page + 1} of {totalPages}
            </span>
            <button
              onClick={() => setPage((p) => (p + 1 < totalPages ? p + 1 : p))}
              disabled={page + 1 >= totalPages}
              className="px-3 py-1.5 rounded-lg border border-gray-200 text-sm disabled:opacity-40"
            >
              Next →
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
