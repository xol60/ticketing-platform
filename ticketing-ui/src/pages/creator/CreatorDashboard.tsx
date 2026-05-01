import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { Card, CardHeader, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { Input } from '../../components/ui/Input';
import { EventStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import type { Event } from '../../types';

function EventStatusActions({ event }: { event: Event }) {
  const queryClient = useQueryClient();
  const inv = () => queryClient.invalidateQueries({ queryKey: ['events'] });

  const openMut   = useMutation({ mutationFn: () => ticketsApi.openEvent(event.id),   onSuccess: inv });
  const closeMut  = useMutation({ mutationFn: () => ticketsApi.closeEvent(event.id),  onSuccess: inv });
  const cancelMut = useMutation({ mutationFn: () => ticketsApi.cancelEvent(event.id), onSuccess: inv });

  return (
    <div className="flex items-center gap-2">
      {event.status === 'DRAFT' && (
        <Button size="sm" loading={openMut.isPending} onClick={() => openMut.mutate()}>
          Open for sale
        </Button>
      )}
      {event.status === 'OPEN' && (
        <Button size="sm" variant="secondary" loading={closeMut.isPending} onClick={() => closeMut.mutate()}>
          Close sales
        </Button>
      )}
      {event.status !== 'CANCELLED' && event.status !== 'CLOSED' && (
        <Button size="sm" variant="danger" loading={cancelMut.isPending} onClick={() => cancelMut.mutate()}>
          Cancel
        </Button>
      )}
    </div>
  );
}

function CreateEventModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ name: '', eventDate: '', salesOpenAt: '', salesCloseAt: '' });
  const [error, setError] = useState('');

  const mutation = useMutation({
    mutationFn: () => ticketsApi.createEvent({
      name: form.name,
      eventDate: new Date(form.eventDate).toISOString(),
      salesOpenAt: new Date(form.salesOpenAt).toISOString(),
      salesCloseAt: new Date(form.salesCloseAt).toISOString(),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['events'] });
      setForm({ name: '', eventDate: '', salesOpenAt: '', salesCloseAt: '' });
      onClose();
    },
    onError: () => setError('Failed to create event. Please check your inputs.'),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Create new event"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button loading={mutation.isPending} onClick={() => mutation.mutate()}
            disabled={!form.name || !form.eventDate || !form.salesOpenAt || !form.salesCloseAt}>
            Create event
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <p className="text-red-600 text-xs bg-red-50 px-3 py-2 rounded-lg">{error}</p>}
        <Input label="Event name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="e.g. Rock Concert 2026" />
        <Input label="Event date & time" type="datetime-local" value={form.eventDate} onChange={(e) => setForm({ ...form, eventDate: e.target.value })} />
        <Input label="Sales open" type="datetime-local" value={form.salesOpenAt} onChange={(e) => setForm({ ...form, salesOpenAt: e.target.value })} />
        <Input label="Sales close" type="datetime-local" value={form.salesCloseAt} onChange={(e) => setForm({ ...form, salesCloseAt: e.target.value })} />
      </div>
    </Modal>
  );
}

export function CreatorDashboard() {
  const [createOpen, setCreateOpen] = useState(false);

  const { data: events = [], isLoading } = useQuery({
    queryKey: ['events'],
    queryFn: ticketsApi.listEvents,
  });

  const { data: allTickets = [] } = useQuery({
    queryKey: ['tickets'],
    queryFn: ticketsApi.listAll,
  });

  const stats = (eventId: string) => {
    const t = allTickets.filter((t) => t.eventId === eventId);
    return {
      total: t.length,
      available: t.filter((t) => t.status === 'AVAILABLE').length,
      sold: t.filter((t) => t.status === 'CONFIRMED').length,
    };
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Creator Dashboard</h1>
          <p className="text-gray-500 text-sm mt-1">Manage your events, tickets, and pricing</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>+ New Event</Button>
      </div>

      {isLoading && <PageSpinner />}

      {!isLoading && events.length === 0 && (
        <div className="text-center py-20 text-gray-400 bg-white rounded-2xl border border-gray-100">
          <p className="text-5xl mb-4">🎭</p>
          <p className="text-lg font-medium">No events yet</p>
          <p className="text-sm mt-1 mb-4">Create your first event to get started</p>
          <Button onClick={() => setCreateOpen(true)}>Create event</Button>
        </div>
      )}

      <div className="flex flex-col gap-4">
        {events.map((event: Event) => {
          const s = stats(event.id);
          return (
            <Card key={event.id}>
              <CardHeader
                title={event.name}
                subtitle={`📅 ${new Date(event.eventDate).toLocaleDateString('en-US', { dateStyle: 'long' })}`}
                action={<EventStatusBadge status={event.status} />}
              />
              <CardBody>
                {/* Stats */}
                <div className="grid grid-cols-3 gap-4 mb-4">
                  {[
                    { label: 'Total tickets', value: s.total },
                    { label: 'Available', value: s.available },
                    { label: 'Sold', value: s.sold },
                  ].map((stat) => (
                    <div key={stat.label} className="text-center bg-gray-50 rounded-xl py-3">
                      <p className="text-xl font-bold text-gray-900">{stat.value}</p>
                      <p className="text-xs text-gray-400">{stat.label}</p>
                    </div>
                  ))}
                </div>

                {/* Actions */}
                <div className="flex items-center justify-between flex-wrap gap-2">
                  <div className="flex items-center gap-2">
                    <Link to={`/creator/events/${event.id}/tickets`}>
                      <Button variant="secondary" size="sm">🎫 Manage tickets</Button>
                    </Link>
                    <Link to={`/creator/events/${event.id}/pricing`}>
                      <Button variant="secondary" size="sm">💰 Pricing</Button>
                    </Link>
                  </div>
                  <EventStatusActions event={event} />
                </div>
              </CardBody>
            </Card>
          );
        })}
      </div>

      <CreateEventModal open={createOpen} onClose={() => setCreateOpen(false)} />
    </div>
  );
}
