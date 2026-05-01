import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useQuery, useMutation } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { pricingApi } from '../../api/pricing';
import { reservationsApi } from '../../api/reservations';
import { ordersApi } from '../../api/orders';
import { useAuth } from '../../context/AuthContext';
import { Card, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge, TicketStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import { Modal } from '../../components/ui/Modal';
import type { Order } from '../../types';

export function TicketDetailPage() {
  const { ticketId } = useParams<{ ticketId: string }>();
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [confirmBuy, setConfirmBuy] = useState(false);
  const [waitlistConfirm, setWaitlistConfirm] = useState(false);

  const { data: ticket, isLoading: ticketLoading } = useQuery({
    queryKey: ['ticket', ticketId],
    queryFn: () => ticketsApi.getTicket(ticketId!),
    enabled: !!ticketId,
  });

  const { data: priceData, isLoading: priceLoading } = useQuery({
    queryKey: ['ticket-price', ticketId],
    queryFn: () => pricingApi.getTicketPrice(ticketId!),
    enabled: !!ticketId && ticket?.status === 'AVAILABLE',
    refetchInterval: 10_000,
  });

  const { data: position } = useQuery({
    queryKey: ['waitlist-position', ticketId],
    queryFn: () => reservationsApi.getPosition(ticketId!),
    enabled: !!ticketId && isAuthenticated && ticket?.status !== 'AVAILABLE',
    retry: false,
  });

  const buyMutation = useMutation({
    mutationFn: () => ordersApi.create({ ticketId: ticketId!, userPrice: currentPrice }),
    onSuccess: (order: Order) => navigate(`/orders/${order.id}/track`),
  });

  const joinWaitlistMutation = useMutation({
    mutationFn: () => reservationsApi.join(ticketId!),
    onSuccess: () => setWaitlistConfirm(false),
  });

  const leaveWaitlistMutation = useMutation({
    mutationFn: () => reservationsApi.leave(position!.reservationId),
  });

  if (ticketLoading) return <PageSpinner />;
  if (!ticket) return (
    <div className="text-center py-20 text-gray-400">
      <p className="text-5xl mb-4">🔍</p>
      <p className="text-lg">Ticket not found</p>
      <Link to="/" className="text-blue-600 text-sm hover:underline mt-2 inline-block">Back to events</Link>
    </div>
  );

  const currentPrice = priceData?.price ?? ticket.facePrice;
  const hasSurge = priceData?.price != null && priceData.price > ticket.facePrice;
  const surgePercent = hasSurge ? Math.round(((priceData!.price - ticket.facePrice) / ticket.facePrice) * 100) : 0;
  const isAvailable = ticket.status === 'AVAILABLE';

  return (
    <div className="max-w-xl mx-auto flex flex-col gap-6">
      {/* Breadcrumb */}
      <nav className="text-sm text-gray-400 flex items-center gap-2">
        <Link to="/" className="hover:text-blue-600 transition-colors">Events</Link>
        <span>/</span>
        <Link to={`/events/${ticket.eventId}`} className="hover:text-blue-600 transition-colors">
          {ticket.eventName}
        </Link>
        <span>/</span>
        <span className="text-gray-700">Seat {ticket.seat}</span>
      </nav>

      {/* Ticket Card */}
      <Card>
        <div className={`h-2 rounded-t-xl ${isAvailable ? 'bg-gradient-to-r from-blue-500 to-indigo-500' : 'bg-gray-200'}`} />
        <CardBody className="flex flex-col gap-5">
          <div className="flex items-start justify-between">
            <div>
              <h1 className="text-xl font-bold text-gray-900">{ticket.eventName}</h1>
              <p className="text-gray-500 text-sm mt-1">
                {ticket.section ? `Section ${ticket.section} · ` : ''}
                {ticket.row ? `Row ${ticket.row} · ` : ''}
                Seat {ticket.seat}
              </p>
            </div>
            <TicketStatusBadge status={ticket.status} />
          </div>

          <hr className="border-gray-100" />

          {/* Pricing */}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-500 mb-1">Current price</p>
              {priceLoading ? (
                <p className="text-2xl font-bold text-gray-300 animate-pulse">$—.—</p>
              ) : (
                <div className="flex items-baseline gap-2">
                  <p className={`text-3xl font-bold ${hasSurge ? 'text-orange-500' : 'text-gray-900'}`}>
                    ${currentPrice.toFixed(2)}
                  </p>
                  {hasSurge && (
                    <p className="text-sm text-gray-400 line-through">${ticket.facePrice.toFixed(2)}</p>
                  )}
                </div>
              )}
              {hasSurge && (
                <div className="flex items-center gap-1 mt-1">
                  <Badge label={`+${surgePercent}% surge`} variant="yellow" />
                  <span className="text-xs text-gray-400">due to high demand</span>
                </div>
              )}
            </div>
            <div className="text-right text-sm text-gray-400">
              <p>Face value</p>
              <p className="font-medium text-gray-600">${ticket.facePrice.toFixed(2)}</p>
            </div>
          </div>

          <hr className="border-gray-100" />

          {/* Action */}
          {isAvailable ? (
            isAuthenticated ? (
              <Button size="lg" className="w-full" onClick={() => setConfirmBuy(true)}>
                🎫 Buy this ticket — ${currentPrice.toFixed(2)}
              </Button>
            ) : (
              <Link to="/login" state={{ from: `/tickets/${ticketId}` }}>
                <Button size="lg" variant="secondary" className="w-full">
                  Sign in to purchase
                </Button>
              </Link>
            )
          ) : (
            <div className="flex flex-col gap-3">
              <div className="bg-yellow-50 border border-yellow-200 rounded-xl px-4 py-3 text-sm text-yellow-800">
                This ticket is currently unavailable.
                {position && <span> You are <strong>#{position.position}</strong> in the waitlist.</span>}
              </div>
              {isAuthenticated && !position && (
                <Button variant="secondary" className="w-full" onClick={() => setWaitlistConfirm(true)}>
                  🔔 Join waitlist
                </Button>
              )}
              {position && (
                <Button variant="ghost" size="sm"
                  loading={leaveWaitlistMutation.isPending}
                  onClick={() => leaveWaitlistMutation.mutate()}
                >
                  Leave waitlist
                </Button>
              )}
            </div>
          )}
        </CardBody>
      </Card>

      {/* Buy confirmation modal */}
      <Modal
        open={confirmBuy}
        onClose={() => setConfirmBuy(false)}
        title="Confirm purchase"
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmBuy(false)}>Cancel</Button>
            <Button loading={buyMutation.isPending} onClick={() => buyMutation.mutate()}>
              Confirm — ${currentPrice.toFixed(2)}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3 text-sm text-gray-600">
          <p>You're about to purchase:</p>
          <div className="bg-gray-50 rounded-xl px-4 py-3 flex flex-col gap-1">
            <p className="font-semibold text-gray-900">{ticket.eventName}</p>
            <p>{ticket.section ? `Section ${ticket.section} · ` : ''}Row {ticket.row ?? '—'} · Seat {ticket.seat}</p>
            <p className="text-blue-600 font-bold text-base mt-1">${currentPrice.toFixed(2)}</p>
          </div>
          {hasSurge && (
            <p className="text-yellow-700 bg-yellow-50 rounded-lg px-3 py-2 text-xs">
              ⚠️ This price includes a surge due to high demand. The final charge may differ if the price changes during checkout.
            </p>
          )}
          {buyMutation.isError && (
            <p className="text-red-600 bg-red-50 rounded-lg px-3 py-2">
              Purchase failed. Please try again.
            </p>
          )}
        </div>
      </Modal>

      {/* Waitlist modal */}
      <Modal
        open={waitlistConfirm}
        onClose={() => setWaitlistConfirm(false)}
        title="Join waitlist"
        footer={
          <>
            <Button variant="secondary" onClick={() => setWaitlistConfirm(false)}>Cancel</Button>
            <Button
              loading={joinWaitlistMutation.isPending}
              onClick={() => joinWaitlistMutation.mutate()}
            >
              Join waitlist
            </Button>
          </>
        }
      >
        <p className="text-sm text-gray-600">
          We'll notify you when this ticket becomes available. You'll be placed in the queue and have a limited time to complete the purchase.
        </p>
      </Modal>
    </div>
  );
}
