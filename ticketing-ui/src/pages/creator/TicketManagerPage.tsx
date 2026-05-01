import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { Card, CardHeader, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { Input } from '../../components/ui/Input';
import { TicketStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';

export function TicketManagerPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const queryClient = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<string | null>(null);
  const [form, setForm] = useState({ section: '', row: '', seat: '', facePrice: '' });

  const { data: events = [] } = useQuery({ queryKey: ['events'], queryFn: ticketsApi.listEvents });
  const { data: allTickets = [], isLoading } = useQuery({ queryKey: ['tickets'], queryFn: ticketsApi.listAll });

  const event    = events.find((e) => e.id === eventId);
  const tickets  = allTickets.filter((t) => t.eventId === eventId);
  const inv      = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });

  const createMut = useMutation({
    mutationFn: () => ticketsApi.createTicket({
      eventId: eventId!,
      section: form.section || undefined,
      row: form.row || undefined,
      seat: form.seat,
      facePrice: parseFloat(form.facePrice),
    }),
    onSuccess: () => { inv(); setAddOpen(false); setForm({ section: '', row: '', seat: '', facePrice: '' }); },
  });

  const updateMut = useMutation({
    mutationFn: (id: string) => ticketsApi.updateTicket(id, {
      section: form.section || undefined,
      row: form.row || undefined,
      seat: form.seat || undefined,
      facePrice: form.facePrice ? parseFloat(form.facePrice) : undefined,
    }),
    onSuccess: () => { inv(); setEditTarget(null); },
  });

  const deleteMut = useMutation({
    mutationFn: ticketsApi.deleteTicket,
    onSuccess: inv,
  });

  const openEdit = (id: string) => {
    const t = tickets.find((t) => t.id === id);
    if (!t) return;
    setForm({ section: t.section ?? '', row: t.row ?? '', seat: t.seat, facePrice: String(t.facePrice) });
    setEditTarget(id);
  };

  const formFields = (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Input label="Section" value={form.section} onChange={(e) => setForm({ ...form, section: e.target.value })} placeholder="A" />
        <Input label="Row" value={form.row} onChange={(e) => setForm({ ...form, row: e.target.value })} placeholder="1" />
      </div>
      <Input label="Seat *" value={form.seat} onChange={(e) => setForm({ ...form, seat: e.target.value })} placeholder="42" required />
      <Input label="Face price ($) *" type="number" min="0.01" step="0.01" value={form.facePrice}
        onChange={(e) => setForm({ ...form, facePrice: e.target.value })} placeholder="50.00" required />
    </div>
  );

  return (
    <div className="flex flex-col gap-6">
      <nav className="text-sm text-gray-400 flex items-center gap-2">
        <Link to="/creator" className="hover:text-blue-600 transition-colors">My Events</Link>
        <span>/</span>
        <span className="text-gray-700 font-medium">{event?.name ?? 'Event'}</span>
        <span>/</span>
        <span className="text-gray-700">Tickets</span>
      </nav>

      <Card>
        <CardHeader
          title={`Tickets — ${event?.name ?? eventId}`}
          subtitle={`${tickets.length} total · ${tickets.filter((t) => t.status === 'AVAILABLE').length} available`}
          action={<Button size="sm" onClick={() => { setForm({ section: '', row: '', seat: '', facePrice: '' }); setAddOpen(true); }}>+ Add ticket</Button>}
        />
        <CardBody className="p-0">
          {isLoading && <div className="p-6"><PageSpinner /></div>}
          {!isLoading && tickets.length === 0 && (
            <div className="text-center py-12 text-gray-400">
              <p className="text-4xl mb-3">🎫</p>
              <p>No tickets yet. Add some to get started.</p>
            </div>
          )}
          {tickets.length > 0 && (
            <div className="divide-y divide-gray-50">
              {tickets.map((t) => (
                <div key={t.id} className="flex items-center justify-between px-6 py-3 hover:bg-gray-50 transition-colors">
                  <div className="flex items-center gap-4">
                    <div className="text-sm">
                      <p className="font-medium text-gray-900">
                        {t.section ? `Section ${t.section} · ` : ''}Row {t.row ?? '—'} · Seat {t.seat}
                      </p>
                      <p className="text-gray-400 text-xs">${t.facePrice.toFixed(2)} face value</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <TicketStatusBadge status={t.status} />
                    {t.status === 'AVAILABLE' && (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => openEdit(t.id)}>Edit</Button>
                        <Button size="sm" variant="danger"
                          loading={deleteMut.isPending && deleteMut.variables !== undefined && deleteMut.variables === t.id}
                          onClick={() => deleteMut.mutate(t.id)}>
                          Delete
                        </Button>
                      </>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardBody>
      </Card>

      {/* Add ticket modal */}
      <Modal
        open={addOpen}
        onClose={() => setAddOpen(false)}
        title="Add ticket"
        footer={
          <>
            <Button variant="secondary" onClick={() => setAddOpen(false)}>Cancel</Button>
            <Button loading={createMut.isPending} onClick={() => createMut.mutate()}
              disabled={!form.seat || !form.facePrice}>
              Add ticket
            </Button>
          </>
        }
      >
        {formFields}
      </Modal>

      {/* Edit ticket modal */}
      <Modal
        open={!!editTarget}
        onClose={() => setEditTarget(null)}
        title="Edit ticket"
        footer={
          <>
            <Button variant="secondary" onClick={() => setEditTarget(null)}>Cancel</Button>
            <Button loading={updateMut.isPending} onClick={() => editTarget && updateMut.mutate(editTarget)}>
              Save changes
            </Button>
          </>
        }
      >
        {formFields}
      </Modal>
    </div>
  );
}
