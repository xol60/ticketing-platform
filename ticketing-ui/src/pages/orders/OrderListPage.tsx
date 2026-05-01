import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '../../api/orders';
import { ticketsApi } from '../../api/tickets';
import { OrderStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import type { Order, Ticket } from '../../types';

function OrderRow({ order, tickets }: { order: Order; tickets: Ticket[] }) {
  const ticket = tickets.find((t) => t.id === order.ticketId);
  const isActive = order.status === 'PENDING' || order.status === 'PRICE_CHANGED';

  return (
    <Link to={isActive ? `/orders/${order.id}/track` : `/orders/${order.id}`}
      className="flex items-center justify-between px-5 py-4 hover:bg-gray-50 transition-colors group">
      <div className="flex flex-col gap-0.5">
        <p className="text-sm font-semibold text-gray-900 group-hover:text-blue-600 transition-colors">
          {ticket ? `${ticket.eventName} · Seat ${ticket.seat}` : `Order ${order.id.slice(0, 8)}…`}
        </p>
        <p className="text-xs text-gray-400">{new Date(order.createdAt).toLocaleString()}</p>
        {order.failureReason && (
          <p className="text-xs text-red-500">{order.failureReason}</p>
        )}
      </div>
      <div className="flex items-center gap-4 shrink-0">
        <div className="text-right">
          <p className="text-sm font-bold text-gray-900">
            ${(order.finalPrice ?? order.requestedPrice).toFixed(2)}
          </p>
          {order.finalPrice != null && order.finalPrice !== order.requestedPrice && (
            <p className="text-xs text-gray-400 line-through">${order.requestedPrice.toFixed(2)}</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <OrderStatusBadge status={order.status} />
          {isActive && <span className="text-xs text-blue-600">→ Track</span>}
        </div>
      </div>
    </Link>
  );
}

export function OrderListPage() {
  const { data: orders = [], isLoading: ordersLoading } = useQuery({
    queryKey: ['my-orders'],
    queryFn: ordersApi.listMyOrders,
  });

  const { data: tickets = [], isLoading: ticketsLoading } = useQuery({
    queryKey: ['tickets'],
    queryFn: ticketsApi.listAll,
  });

  const isLoading = ordersLoading || ticketsLoading;

  const active   = orders.filter((o) => o.status === 'PENDING' || o.status === 'PRICE_CHANGED');
  const past     = orders.filter((o) => !['PENDING', 'PRICE_CHANGED'].includes(o.status));

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-bold text-gray-900">My Orders</h1>

      {isLoading && <PageSpinner />}

      {!isLoading && orders.length === 0 && (
        <div className="text-center py-20 text-gray-400 bg-white rounded-2xl border border-gray-100">
          <p className="text-5xl mb-4">📋</p>
          <p className="text-lg font-medium">No orders yet</p>
          <Link to="/" className="text-blue-600 text-sm hover:underline mt-2 inline-block">Browse events</Link>
        </div>
      )}

      {active.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3">Active</h2>
          <div className="bg-white rounded-2xl border border-gray-100 shadow-sm divide-y divide-gray-50">
            {active.map((o) => <OrderRow key={o.id} order={o} tickets={tickets} />)}
          </div>
        </div>
      )}

      {past.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3">History</h2>
          <div className="bg-white rounded-2xl border border-gray-100 shadow-sm divide-y divide-gray-50">
            {past.map((o) => <OrderRow key={o.id} order={o} tickets={tickets} />)}
          </div>
        </div>
      )}
    </div>
  );
}
