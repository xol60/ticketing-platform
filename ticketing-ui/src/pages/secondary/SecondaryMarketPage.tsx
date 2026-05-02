import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueries, useMutation, useQueryClient } from '@tanstack/react-query';
import { secondaryApi } from '../../api/secondary';
import { ticketsApi } from '../../api/tickets';
import { useAuth } from '../../context/AuthContext';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { PageSpinner } from '../../components/ui/Spinner';
import type { Listing, Order, Ticket } from '../../types';

function ListingCard({ listing, tickets, onBuy }: { listing: Listing; tickets: Ticket[]; onBuy: (listing: Listing) => void }) {
  const { user } = useAuth();
  const ticket = tickets.find((t) => t.id === listing.ticketId);
  const isMine = listing.sellerId === user?.id;

  return (
    <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5 flex flex-col gap-4 hover:shadow-md hover:border-blue-100 transition-all">
      <div className="flex items-start justify-between">
        <div>
          <p className="font-semibold text-gray-900">
            {ticket?.eventName ?? `Event ${listing.eventId.slice(0, 6)}…`}
          </p>
          <p className="text-sm text-gray-400 mt-0.5">
            {ticket ? `${ticket.section ? `Section ${ticket.section} · ` : ''}Row ${ticket.row ?? '—'} · Seat ${ticket.seat}` : `Ticket ${listing.ticketId.slice(0, 8)}…`}
          </p>
        </div>
        {isMine && <Badge label="Your listing" variant="purple" />}
      </div>

      <div className="flex items-center justify-between">
        <div>
          <p className="text-xs text-gray-400">Asking price</p>
          <p className="text-2xl font-bold text-blue-600">${listing.askPrice.toFixed(2)}</p>
        </div>
        <div className="text-right text-xs text-gray-400">
          <p>Listed {new Date(listing.createdAt).toLocaleDateString()}</p>
        </div>
      </div>

      {!isMine && listing.status === 'ACTIVE' && (
        <Button className="w-full" onClick={() => onBuy(listing)}>
          Buy this ticket
        </Button>
      )}
    </div>
  );
}

export function SecondaryMarketPage() {
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [buyTarget, setBuyTarget] = useState<Listing | null>(null);

  const { data: listings = [], isLoading: listingsLoading } = useQuery({
    queryKey: ['listings'],
    queryFn: secondaryApi.listAll,
  });

  // Derive unique eventIds from loaded listings, then fetch tickets per event.
  // Shares the ['tickets', eventId] cache with EventDetailPage.
  const uniqueEventIds = useMemo(
    () => [...new Set(listings.map((l) => l.eventId))],
    [listings]
  );

  const ticketQueries = useQueries({
    queries: uniqueEventIds.map((eventId) => ({
      queryKey: ['tickets', eventId],
      queryFn:  () => ticketsApi.listByEvent(eventId),
    })),
  });

  const tickets: Ticket[] = useMemo(
    () => ticketQueries.flatMap((q) => q.data ?? []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [JSON.stringify(ticketQueries.map((q) => q.dataUpdatedAt))]
  );

  const ticketsLoading = ticketQueries.some((q) => q.isLoading);

  const purchaseMutation = useMutation({
    mutationFn: (id: string) => secondaryApi.purchase(id),
    onSuccess: (order: Order) => {
      queryClient.invalidateQueries({ queryKey: ['listings'] });
      setBuyTarget(null);
      navigate(`/orders/${order.id}/track`);
    },
  });

  const active = listings.filter((l) => l.status === 'ACTIVE');
  const isLoading = listingsLoading || ticketsLoading;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Resale Market</h1>
          <p className="text-gray-500 text-sm mt-1">Buy tickets resold by other fans</p>
        </div>
        {isAuthenticated && (
          <Link to="/orders">
            <Button variant="secondary" size="sm">My listings</Button>
          </Link>
        )}
      </div>

      {isLoading && <PageSpinner />}

      {!isLoading && active.length === 0 && (
        <div className="text-center py-20 text-gray-400 bg-white rounded-2xl border border-gray-100">
          <p className="text-5xl mb-4">🏷️</p>
          <p className="text-lg font-medium">No active listings</p>
          <p className="text-sm mt-1">Check back later or browse primary tickets</p>
          <Link to="/" className="text-blue-600 text-sm hover:underline mt-3 inline-block">Browse events</Link>
        </div>
      )}

      {!isLoading && active.length > 0 && (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {active.map((l) => (
            <ListingCard
              key={l.id}
              listing={l}
              tickets={tickets}
              onBuy={isAuthenticated ? setBuyTarget : () => navigate('/login')}
            />
          ))}
        </div>
      )}

      {/* Buy confirmation modal */}
      <Modal
        open={!!buyTarget}
        onClose={() => setBuyTarget(null)}
        title="Confirm purchase"
        footer={
          <>
            <Button variant="secondary" onClick={() => setBuyTarget(null)}>Cancel</Button>
            <Button
              loading={purchaseMutation.isPending}
              onClick={() => buyTarget && purchaseMutation.mutate(buyTarget.id)}
            >
              Confirm — ${buyTarget?.askPrice.toFixed(2)}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3 text-sm text-gray-600">
          <p>You're about to purchase a resale ticket.</p>
          {buyTarget && (
            <div className="bg-gray-50 rounded-xl px-4 py-3">
              <p className="text-blue-600 font-bold text-base">${buyTarget.askPrice.toFixed(2)}</p>
              <p className="text-gray-400 text-xs mt-1">Ticket ID: {buyTarget.ticketId.slice(0, 8)}…</p>
            </div>
          )}
          {purchaseMutation.isError && (
            <p className="text-red-600 bg-red-50 rounded-lg px-3 py-2 text-xs">Purchase failed. Please try again.</p>
          )}
        </div>
      </Modal>
    </div>
  );
}
