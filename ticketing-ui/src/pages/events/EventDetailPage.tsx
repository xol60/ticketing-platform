import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { pricingApi } from '../../api/pricing';
import { EventStatusBadge, TicketStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import { Button } from '../../components/ui/Button';
import { useAuth } from '../../context/AuthContext';
import type { Ticket } from '../../types';

function TicketRow({ ticket, eventOpen }: { ticket: Ticket; eventOpen: boolean }) {
  const { isAuthenticated } = useAuth();

  const { data: priceData } = useQuery({
    queryKey: ['ticket-price', ticket.id],
    queryFn: () => pricingApi.getTicketPrice(ticket.id),
    refetchInterval: 15_000,
    enabled: ticket.status === 'AVAILABLE',
  });

  const displayPrice = priceData?.price ?? ticket.facePrice;
  const hasSurge = priceData?.price != null && priceData.price > ticket.facePrice;

  return (
    <div className={`flex items-center justify-between px-4 py-3 rounded-xl border transition-colors
      ${ticket.status === 'AVAILABLE' ? 'border-gray-100 hover:border-blue-100 bg-white hover:bg-blue-50/20' : 'border-gray-100 bg-gray-50'}`}
    >
      <div className="flex items-center gap-4">
        <div className="text-sm">
          <p className="font-medium text-gray-900">
            {ticket.section ? `${ticket.section} · ` : ''}Row {ticket.row ?? '—'} · Seat {ticket.seat}
          </p>
          <p className="text-gray-400 text-xs mt-0.5">ID: {ticket.id.slice(0, 8)}…</p>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <div className="text-right">
          <p className={`font-bold text-base ${hasSurge ? 'text-orange-600' : 'text-gray-900'}`}>
            ${displayPrice.toFixed(2)}
          </p>
          {hasSurge && (
            <p className="text-xs text-gray-400 line-through">${ticket.facePrice.toFixed(2)}</p>
          )}
        </div>
        <TicketStatusBadge status={ticket.status} />
        {ticket.status === 'AVAILABLE' && eventOpen && (
          isAuthenticated
            ? <Link to={`/tickets/${ticket.id}`}>
                <Button size="sm">Buy</Button>
              </Link>
            : <Link to="/login" state={{ from: `/tickets/${ticket.id}` }}>
                <Button size="sm" variant="secondary">Sign in to buy</Button>
              </Link>
        )}
      </div>
    </div>
  );
}

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();

  const { data: events = [], isLoading: eventsLoading } = useQuery({
    queryKey: ['events'],
    queryFn: ticketsApi.listEvents,
  });

  const { data: allTickets = [], isLoading: ticketsLoading } = useQuery({
    queryKey: ['tickets'],
    queryFn: ticketsApi.listAll,
  });

  const event = events.find((e) => e.id === eventId);
  const tickets = allTickets.filter((t) => t.eventId === eventId);
  const available = tickets.filter((t) => t.status === 'AVAILABLE');

  const isLoading = eventsLoading || ticketsLoading;
  if (isLoading) return <PageSpinner />;
  if (!event) return (
    <div className="text-center py-20 text-gray-400">
      <p className="text-5xl mb-4">🔍</p>
      <p className="text-lg font-medium">Event not found</p>
      <Link to="/" className="text-blue-600 text-sm hover:underline mt-2 inline-block">Back to events</Link>
    </div>
  );

  const eventOpen = event.status === 'OPEN';

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
          <div className="flex flex-wrap gap-4 text-sm text-gray-500 mt-1">
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
        <div className="text-right shrink-0">
          <p className="text-2xl font-bold text-blue-600">{available.length}</p>
          <p className="text-sm text-gray-400">tickets available</p>
        </div>
      </div>

      {/* Tickets */}
      <div className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold text-gray-800">
          Tickets
          <span className="ml-2 text-sm font-normal text-gray-400">({tickets.length} total)</span>
        </h2>

        {tickets.length === 0 ? (
          <div className="text-center py-16 text-gray-400 bg-white rounded-2xl border border-gray-100">
            <p className="text-4xl mb-3">🎫</p>
            <p>No tickets available for this event yet.</p>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {/* Available first */}
            {available.length > 0 && (
              <>
                <p className="text-xs font-medium text-gray-400 uppercase tracking-wider px-1">Available</p>
                {available.map((t) => <TicketRow key={t.id} ticket={t} eventOpen={eventOpen} />)}
              </>
            )}
            {tickets.filter((t) => t.status !== 'AVAILABLE').length > 0 && (
              <>
                <p className="text-xs font-medium text-gray-400 uppercase tracking-wider px-1 mt-2">Sold / Reserved</p>
                {tickets.filter((t) => t.status !== 'AVAILABLE').map((t) => (
                  <TicketRow key={t.id} ticket={t} eventOpen={eventOpen} />
                ))}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
