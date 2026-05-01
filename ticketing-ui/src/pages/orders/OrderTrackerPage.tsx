import { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ordersApi } from '../../api/orders';
import { ticketsApi } from '../../api/tickets';
import { useOrderStream } from '../../hooks/useOrderStream';
import { OrderStatusBadge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { PageSpinner } from '../../components/ui/Spinner';
import type { Order } from '../../types';

function ProgressStep({ label, state }: { label: string; state: 'done' | 'active' | 'pending' }) {
  return (
    <div className="flex items-center gap-3">
      <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold shrink-0
        ${state === 'done'   ? 'bg-green-500 text-white'
        : state === 'active' ? 'bg-blue-600 text-white animate-pulse'
        :                      'bg-gray-100 text-gray-400'}`}>
        {state === 'done' ? '✓' : ''}
      </div>
      <span className={`text-sm ${state === 'active' ? 'text-blue-600 font-semibold' : state === 'done' ? 'text-gray-700' : 'text-gray-400'}`}>
        {label}
      </span>
    </div>
  );
}

function Countdown({ seconds }: { seconds: number }) {
  const [remaining, setRemaining] = useState(seconds);
  useEffect(() => {
    const t = setInterval(() => setRemaining((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, []);
  const m = Math.floor(remaining / 60);
  const s = remaining % 60;
  const urgent = remaining < 60;
  return (
    <span className={`font-mono font-bold ${urgent ? 'text-red-600' : 'text-orange-600'}`}>
      {m}:{s.toString().padStart(2, '0')}
    </span>
  );
}

export function OrderTrackerPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const queryClient = useQueryClient();
  const [priceModalOpen, setPriceModalOpen] = useState(false);
  const shownModalRef = useRef(false);

  const { data: order, isLoading } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => ordersApi.getOrder(orderId!),
    enabled: !!orderId,
    refetchInterval: (query: { state: { data?: Order } }) => {
      const s = query.state.data?.status;
      return s === 'CONFIRMED' || s === 'FAILED' || s === 'CANCELLED' ? false : 5000;
    },
  });

  const { data: ticket } = useQuery({
    queryKey: ['ticket', order?.ticketId],
    queryFn: () => ticketsApi.getTicket(order!.ticketId),
    enabled: !!order?.ticketId,
  });

  const confirmMutation = useMutation({
    mutationFn: () => ordersApi.confirmPrice(orderId!),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['order', orderId] }); setPriceModalOpen(false); },
  });
  const cancelMutation = useMutation({
    mutationFn: () => ordersApi.cancelPrice(orderId!),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['order', orderId] }); setPriceModalOpen(false); },
  });

  // SSE stream for real-time updates
  useOrderStream(orderId ?? null, (event) => {
    queryClient.invalidateQueries({ queryKey: ['order', orderId] });
    if (event.type === 'PRICE_CHANGED' && !shownModalRef.current) {
      shownModalRef.current = true;
      setPriceModalOpen(true);
    }
  });

  // Auto-open price modal if order already has PRICE_CHANGED status on load
  useEffect(() => {
    if (order?.status === 'PRICE_CHANGED' && !shownModalRef.current) {
      shownModalRef.current = true;
      setPriceModalOpen(true);
    }
  }, [order?.status]);

  if (isLoading) return <PageSpinner />;
  if (!order) return (
    <div className="text-center py-20 text-gray-400">
      <p className="text-5xl mb-4">🔍</p>
      <p>Order not found</p>
    </div>
  );

  const terminal = ['CONFIRMED', 'FAILED', 'CANCELLED'].includes(order.status);
  const statusStep =
    order.status === 'PENDING'   ? 1 :
    order.status === 'PRICE_CHANGED' ? 2 :
    order.status === 'CONFIRMED' ? 4 : -1;

  const steps = [
    'Order placed',
    'Ticket reserved',
    'Price confirmed',
    'Payment processed',
    'Ticket confirmed ✓',
  ];

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Order Status</h1>
        <OrderStatusBadge status={order.status} />
      </div>

      {/* Progress steps */}
      {!['FAILED', 'CANCELLED'].includes(order.status) && (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 flex flex-col gap-4">
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider">Progress</h2>
          <div className="flex flex-col gap-4 relative">
            {/* connector line */}
            <div className="absolute left-4 top-4 bottom-4 w-px bg-gray-100" />
            {steps.map((label, i) => (
              <ProgressStep key={i} label={label}
                state={i < statusStep ? 'done' : i === statusStep ? 'active' : 'pending'} />
            ))}
          </div>
          {!terminal && (
            <div className="flex items-center gap-2 text-xs text-gray-400 border-t border-gray-50 pt-3 mt-1">
              <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse" />
              Live tracking active
            </div>
          )}
        </div>
      )}

      {/* Confirmed ticket */}
      {order.status === 'CONFIRMED' && (
        <div className="bg-green-50 border border-green-200 rounded-2xl p-6 flex flex-col gap-3">
          <div className="flex items-center gap-3">
            <span className="text-3xl">🎉</span>
            <div>
              <p className="font-bold text-green-800">Purchase confirmed!</p>
              <p className="text-sm text-green-600">Your ticket is secured.</p>
            </div>
          </div>
          {ticket && (
            <div className="bg-white rounded-xl px-4 py-3 text-sm">
              <p className="font-semibold text-gray-900">{ticket.eventName}</p>
              <p className="text-gray-500">Seat {ticket.seat}</p>
              <p className="text-blue-600 font-bold mt-1">${order.finalPrice?.toFixed(2)}</p>
            </div>
          )}
          {order.paymentReference && (
            <p className="text-xs text-green-700">Reference: <span className="font-mono">{order.paymentReference}</span></p>
          )}
        </div>
      )}

      {/* Failed */}
      {(order.status === 'FAILED' || order.status === 'CANCELLED') && (
        <div className="bg-red-50 border border-red-200 rounded-2xl p-6 flex flex-col gap-2">
          <div className="flex items-center gap-3">
            <span className="text-3xl">{order.status === 'CANCELLED' ? '🚫' : '❌'}</span>
            <div>
              <p className="font-bold text-red-800">
                {order.status === 'CANCELLED' ? 'Order cancelled' : 'Order failed'}
              </p>
              {order.failureReason && (
                <p className="text-sm text-red-600">{order.failureReason}</p>
              )}
            </div>
          </div>
          <Link to="/">
            <Button variant="secondary" size="sm" className="mt-2">Browse other tickets</Button>
          </Link>
        </div>
      )}

      {/* Order details */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6">
        <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-4">Order Details</h2>
        <dl className="grid grid-cols-2 gap-y-3 text-sm">
          <dt className="text-gray-400">Order ID</dt>
          <dd className="font-mono text-xs text-gray-700 truncate">{order.id}</dd>
          <dt className="text-gray-400">Ticket</dt>
          <dd className="text-gray-700">{ticket ? `${ticket.eventName} · Seat ${ticket.seat}` : order.ticketId.slice(0, 8) + '…'}</dd>
          <dt className="text-gray-400">Requested price</dt>
          <dd className="text-gray-700">${order.requestedPrice.toFixed(2)}</dd>
          {order.finalPrice != null && (
            <>
              <dt className="text-gray-400">Final price</dt>
              <dd className="text-blue-600 font-semibold">${order.finalPrice.toFixed(2)}</dd>
            </>
          )}
          <dt className="text-gray-400">Placed</dt>
          <dd className="text-gray-700">{new Date(order.createdAt).toLocaleString()}</dd>
        </dl>
      </div>

      {/* Price Change Modal */}
      <Modal
        open={priceModalOpen}
        onClose={() => {/* can't dismiss — must accept or reject */}}
        title="⚠️ Price has changed"
        footer={
          <>
            <Button variant="danger" onClick={() => cancelMutation.mutate()} loading={cancelMutation.isPending}>
              Reject & Cancel
            </Button>
            <Button onClick={() => confirmMutation.mutate()} loading={confirmMutation.isPending}>
              Accept new price — ${order.pendingPrice?.toFixed(2)}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4 text-sm text-gray-600">
          <p>The price for this ticket has changed since you placed your order. Please accept or reject the new price to continue.</p>
          <div className="grid grid-cols-2 gap-4">
            <div className="bg-gray-50 rounded-xl p-4 text-center">
              <p className="text-xs text-gray-400 mb-1">Original price</p>
              <p className="text-lg font-bold text-gray-500 line-through">${order.requestedPrice.toFixed(2)}</p>
            </div>
            <div className="bg-blue-50 rounded-xl p-4 text-center">
              <p className="text-xs text-blue-500 mb-1">New price</p>
              <p className="text-lg font-bold text-blue-700">${order.pendingPrice?.toFixed(2)}</p>
            </div>
          </div>
          <div className="flex items-center justify-center gap-2 bg-orange-50 rounded-xl px-4 py-2.5">
            <span className="text-orange-600 text-xs">Time remaining to respond:</span>
            <Countdown seconds={360} />
          </div>
          <p className="text-xs text-gray-400 text-center">
            If you don't respond, the order will be automatically cancelled.
          </p>
        </div>
      </Modal>
    </div>
  );
}
