import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '../../api/orders';
import { ticketsApi } from '../../api/tickets';
import { paymentsApi } from '../../api/payments';
import { secondaryApi } from '../../api/secondary';
import { Card, CardHeader, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { OrderStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Modal } from '../../components/ui/Modal';
import { Input } from '../../components/ui/Input';

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const queryClient = useQueryClient();
  const [listModal, setListModal] = useState(false);
  const [askPrice, setAskPrice] = useState('');

  const { data: order, isLoading } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => ordersApi.getOrder(orderId!),
    enabled: !!orderId,
  });

  const { data: ticket } = useQuery({
    queryKey: ['ticket', order?.ticketId],
    queryFn: () => ticketsApi.getTicket(order!.ticketId),
    enabled: !!order?.ticketId,
  });

  const { data: payment } = useQuery({
    queryKey: ['payment', orderId],
    queryFn: () => paymentsApi.getByOrder(orderId!),
    enabled: !!orderId && order?.status === 'CONFIRMED',
    retry: false,
  });

  const listMutation = useMutation({
    mutationFn: () => secondaryApi.createListing({
      ticketId: order!.ticketId,
      eventId: ticket!.eventId,
      askPrice: parseFloat(askPrice),
    }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['listings'] }); setListModal(false); },
  });

  if (isLoading) return <PageSpinner />;
  if (!order) return (
    <div className="text-center py-20 text-gray-400">
      <p className="text-5xl mb-4">🔍</p><p>Order not found</p>
      <Link to="/orders" className="text-blue-600 text-sm hover:underline mt-2 inline-block">Back to orders</Link>
    </div>
  );

  const isActive = order.status === 'PENDING' || order.status === 'PRICE_CHANGED';
  const canResell = order.status === 'CONFIRMED';

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-6">
      <nav className="text-sm text-gray-400 flex items-center gap-2">
        <Link to="/orders" className="hover:text-blue-600 transition-colors">My Orders</Link>
        <span>/</span>
        <span className="text-gray-700 font-mono">{order.id.slice(0, 8)}…</span>
      </nav>

      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">Order Details</h1>
        <div className="flex items-center gap-3">
          <OrderStatusBadge status={order.status} />
          {isActive && (
            <Link to={`/orders/${order.id}/track`}>
              <Button size="sm">Track order</Button>
            </Link>
          )}
        </div>
      </div>

      <Card>
        <CardHeader title="Ticket" />
        <CardBody>
          {ticket ? (
            <div className="flex items-start justify-between">
              <div>
                <p className="font-semibold text-gray-900">{ticket.eventName}</p>
                <p className="text-sm text-gray-500 mt-0.5">
                  {ticket.section ? `Section ${ticket.section} · ` : ''}Row {ticket.row ?? '—'} · Seat {ticket.seat}
                </p>
              </div>
              <Link to={`/events/${ticket.eventId}`} className="text-xs text-blue-600 hover:underline">View event</Link>
            </div>
          ) : (
            <p className="text-sm text-gray-400 font-mono">{order.ticketId}</p>
          )}
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Payment" />
        <CardBody>
          <dl className="grid grid-cols-2 gap-y-3 text-sm">
            <dt className="text-gray-400">Requested price</dt>
            <dd className="text-gray-700">${order.requestedPrice.toFixed(2)}</dd>
            {order.pendingPrice != null && (
              <>
                <dt className="text-gray-400">Pending price</dt>
                <dd className="text-orange-600 font-medium">${order.pendingPrice.toFixed(2)}</dd>
              </>
            )}
            {order.finalPrice != null && (
              <>
                <dt className="text-gray-400">Final charged</dt>
                <dd className="text-blue-600 font-bold">${order.finalPrice.toFixed(2)}</dd>
              </>
            )}
            {order.paymentReference && (
              <>
                <dt className="text-gray-400">Reference</dt>
                <dd className="font-mono text-xs text-gray-700">{order.paymentReference}</dd>
              </>
            )}
            {payment && (
              <>
                <dt className="text-gray-400">Payment status</dt>
                <dd className="text-gray-700 capitalize">{payment.status.toLowerCase()}</dd>
              </>
            )}
          </dl>
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Order info" />
        <CardBody>
          <dl className="grid grid-cols-2 gap-y-3 text-sm">
            <dt className="text-gray-400">Order ID</dt>
            <dd className="font-mono text-xs text-gray-700 truncate">{order.id}</dd>
            <dt className="text-gray-400">Saga ID</dt>
            <dd className="font-mono text-xs text-gray-700 truncate">{order.sagaId}</dd>
            <dt className="text-gray-400">Placed</dt>
            <dd className="text-gray-700">{new Date(order.createdAt).toLocaleString()}</dd>
            <dt className="text-gray-400">Updated</dt>
            <dd className="text-gray-700">{new Date(order.updatedAt).toLocaleString()}</dd>
            {order.failureReason && (
              <>
                <dt className="text-gray-400">Failure</dt>
                <dd className="text-red-600 text-xs">{order.failureReason}</dd>
              </>
            )}
          </dl>
        </CardBody>
      </Card>

      {canResell && (
        <Button variant="secondary" onClick={() => setListModal(true)}>
          🔄 List this ticket for resale
        </Button>
      )}

      <Modal
        open={listModal}
        onClose={() => setListModal(false)}
        title="List ticket for resale"
        footer={
          <>
            <Button variant="secondary" onClick={() => setListModal(false)}>Cancel</Button>
            <Button loading={listMutation.isPending} onClick={() => listMutation.mutate()}
              disabled={!askPrice || isNaN(parseFloat(askPrice))}>
              Create listing
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <p className="text-sm text-gray-600">
            Set your asking price for <strong>{ticket?.eventName} · Seat {ticket?.seat}</strong>.
          </p>
          <Input
            label="Ask price ($)"
            type="number"
            min="0.01"
            step="0.01"
            value={askPrice}
            onChange={(e) => setAskPrice(e.target.value)}
            placeholder="e.g. 150.00"
          />
          {listMutation.isError && (
            <p className="text-red-600 text-xs">Failed to create listing. Please try again.</p>
          )}
        </div>
      </Modal>
    </div>
  );
}
