import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { Card, CardHeader, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { Input } from '../../components/ui/Input';
import { TicketStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';

// ── Types ──────────────────────────────────────────────────────────────────────

interface SingleForm {
  section: string;
  row: string;
  seat: string;
  facePrice: string;
}

interface BulkForm {
  section: string;
  rowStart: string;
  rowEnd: string;
  seatStart: string;
  seatEnd: string;
  facePrice: string;
}

const EMPTY_SINGLE: SingleForm = { section: '', row: '', seat: '', facePrice: '' };
const EMPTY_BULK: BulkForm    = { section: '', rowStart: '', rowEnd: '', seatStart: '', seatEnd: '', facePrice: '' };

// ── Helpers ────────────────────────────────────────────────────────────────────

/** Calculate how many tickets a bulk form would generate (rough preview). */
function previewCount(f: BulkForm): number | null {
  const sStart = parseInt(f.seatStart, 10);
  const sEnd   = parseInt(f.seatEnd,   10);
  if (isNaN(sStart) || isNaN(sEnd) || sEnd < sStart) return null;
  const seatCount = sEnd - sStart + 1;

  const hasRowRange = f.rowStart.trim() !== '' && f.rowEnd.trim() !== '';
  if (!hasRowRange) return seatCount;  // no row dimension

  const rStart = f.rowStart.trim();
  const rEnd   = f.rowEnd.trim();
  let rowCount = 0;

  // Alphabetical (single letter)
  if (/^[A-Za-z]$/.test(rStart) && /^[A-Za-z]$/.test(rEnd)) {
    const a = rStart.toUpperCase().charCodeAt(0);
    const b = rEnd.toUpperCase().charCodeAt(0);
    rowCount = Math.abs(b - a) + 1;
  } else {
    // Numeric
    const a = parseInt(rStart, 10);
    const b = parseInt(rEnd,   10);
    if (isNaN(a) || isNaN(b)) return null;
    rowCount = Math.abs(b - a) + 1;
  }

  return rowCount * seatCount;
}

// ── Component ──────────────────────────────────────────────────────────────────

export function TicketManagerPage() {
  const { eventId }    = useParams<{ eventId: string }>();
  const queryClient    = useQueryClient();

  // modal state
  const [addOpen,      setAddOpen]      = useState(false);
  const [bulkOpen,     setBulkOpen]     = useState(false);
  const [editTarget,   setEditTarget]   = useState<string | null>(null);
  const [bulkCreated,  setBulkCreated]  = useState<number | null>(null);

  const [form,     setForm]     = useState<SingleForm>(EMPTY_SINGLE);
  const [bulkForm, setBulkForm] = useState<BulkForm>(EMPTY_BULK);

  // ── Data ──────────────────────────────────────────────────────────────────
  const { data: events = [] } = useQuery({ queryKey: ['events'], queryFn: ticketsApi.listEvents });
  const { data: tickets = [], isLoading } = useQuery({
    queryKey: ['tickets', eventId],
    queryFn:  () => ticketsApi.listByEvent(eventId!),
    enabled:  !!eventId,
  });

  const event = events.find((e) => e.id === eventId);
  const inv   = () => queryClient.invalidateQueries({ queryKey: ['tickets', eventId] });

  // ── Mutations ─────────────────────────────────────────────────────────────
  const createMut = useMutation({
    mutationFn: () => ticketsApi.createTicket({
      eventId:   eventId!,
      section:   form.section   || undefined,
      row:       form.row       || undefined,
      seat:      form.seat,
      facePrice: parseFloat(form.facePrice),
    }),
    onSuccess: () => { inv(); setAddOpen(false); setForm(EMPTY_SINGLE); },
  });

  const updateMut = useMutation({
    mutationFn: (id: string) => ticketsApi.updateTicket(id, {
      section:   form.section   || undefined,
      row:       form.row       || undefined,
      seat:      form.seat      || undefined,
      facePrice: form.facePrice ? parseFloat(form.facePrice) : undefined,
    }),
    onSuccess: () => { inv(); setEditTarget(null); },
  });

  const deleteMut = useMutation({
    mutationFn: ticketsApi.deleteTicket,
    onSuccess: inv,
  });

  const bulkMut = useMutation({
    mutationFn: () => ticketsApi.createTicketsBatch({
      eventId:   eventId!,
      eventName: event?.name ?? '',
      section:   bulkForm.section   || undefined,
      rowStart:  bulkForm.rowStart  || undefined,
      rowEnd:    bulkForm.rowEnd    || undefined,
      seatStart: parseInt(bulkForm.seatStart, 10),
      seatEnd:   parseInt(bulkForm.seatEnd,   10),
      facePrice: parseFloat(bulkForm.facePrice),
    }),
    onSuccess: (created) => {
      inv();
      setBulkForm(EMPTY_BULK);
      setBulkCreated(created.length);   // show inline banner; modal stays open briefly
      setTimeout(() => { setBulkOpen(false); setBulkCreated(null); }, 1800);
    },
  });

  // ── Helpers ───────────────────────────────────────────────────────────────
  const openEdit = (id: string) => {
    const t = tickets.find((t) => t.id === id);
    if (!t) return;
    setForm({ section: t.section ?? '', row: t.row ?? '', seat: t.seat, facePrice: String(t.facePrice) });
    setEditTarget(id);
  };

  const bulkPreview = useMemo(() => previewCount(bulkForm), [bulkForm]);
  const bulkValid   =
    bulkPreview !== null &&
    bulkPreview > 0 &&
    bulkForm.facePrice !== '' &&
    !isNaN(parseFloat(bulkForm.facePrice));

  // ── Single-ticket form fields ─────────────────────────────────────────────
  const singleFields = (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Input
          label="Section"
          value={form.section}
          onChange={(e) => setForm({ ...form, section: e.target.value })}
          placeholder="A"
        />
        <Input
          label="Row"
          value={form.row}
          onChange={(e) => setForm({ ...form, row: e.target.value })}
          placeholder="1"
        />
      </div>
      <Input
        label="Seat *"
        value={form.seat}
        onChange={(e) => setForm({ ...form, seat: e.target.value })}
        placeholder="42"
        required
      />
      <Input
        label="Face price ($) *"
        type="number"
        min="0.01"
        step="0.01"
        value={form.facePrice}
        onChange={(e) => setForm({ ...form, facePrice: e.target.value })}
        placeholder="50.00"
        required
      />
    </div>
  );

  // ── Render ────────────────────────────────────────────────────────────────
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
          action={
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                onClick={() => { setBulkForm(EMPTY_BULK); setBulkOpen(true); }}
              >
                Bulk Add
              </Button>
              <Button
                size="sm"
                onClick={() => { setForm(EMPTY_SINGLE); setAddOpen(true); }}
              >
                + Add ticket
              </Button>
            </div>
          }
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
                <div
                  key={t.id}
                  className="flex items-center justify-between px-6 py-3 hover:bg-gray-50 transition-colors"
                >
                  <div className="text-sm">
                    <p className="font-medium text-gray-900">
                      {t.section ? `Section ${t.section} · ` : ''}
                      Row {t.row ?? '—'} · Seat {t.seat}
                    </p>
                    <p className="text-gray-400 text-xs">${t.facePrice.toFixed(2)} face value</p>
                  </div>
                  <div className="flex items-center gap-3">
                    <TicketStatusBadge status={t.status} />
                    {t.status === 'AVAILABLE' && (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => openEdit(t.id)}>Edit</Button>
                        <Button
                          size="sm"
                          variant="danger"
                          loading={deleteMut.isPending && deleteMut.variables === t.id}
                          onClick={() => deleteMut.mutate(t.id)}
                        >
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

      {/* ── Add single ticket modal ─────────────────────────────────────── */}
      <Modal
        open={addOpen}
        onClose={() => setAddOpen(false)}
        title="Add ticket"
        footer={
          <>
            <Button variant="secondary" onClick={() => setAddOpen(false)}>Cancel</Button>
            <Button
              loading={createMut.isPending}
              onClick={() => createMut.mutate()}
              disabled={!form.seat || !form.facePrice}
            >
              Add ticket
            </Button>
          </>
        }
      >
        {singleFields}
      </Modal>

      {/* ── Edit ticket modal ───────────────────────────────────────────── */}
      <Modal
        open={!!editTarget}
        onClose={() => setEditTarget(null)}
        title="Edit ticket"
        footer={
          <>
            <Button variant="secondary" onClick={() => setEditTarget(null)}>Cancel</Button>
            <Button
              loading={updateMut.isPending}
              onClick={() => editTarget && updateMut.mutate(editTarget)}
            >
              Save changes
            </Button>
          </>
        }
      >
        {singleFields}
      </Modal>

      {/* ── Bulk Add modal ──────────────────────────────────────────────── */}
      <Modal
        open={bulkOpen}
        onClose={() => setBulkOpen(false)}
        title="Bulk Add Tickets"
        footer={
          <>
            <Button variant="secondary" onClick={() => setBulkOpen(false)}>Cancel</Button>
            <Button
              loading={bulkMut.isPending}
              disabled={!bulkValid}
              onClick={() => bulkMut.mutate()}
            >
              Create tickets
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <p className="text-sm text-gray-500">
            Define a seat range and the service will generate every combination of
            row × seat, skipping any that already exist.
          </p>

          {/* Section */}
          <Input
            label="Section (optional)"
            value={bulkForm.section}
            onChange={(e) => setBulkForm({ ...bulkForm, section: e.target.value })}
            placeholder="VIP, Floor, Balcony…"
          />

          {/* Row range */}
          <div>
            <p className="text-xs font-medium text-gray-600 mb-1">
              Row range{' '}
              <span className="font-normal text-gray-400">
                (optional — letters A–Z or numbers 1–99)
              </span>
            </p>
            <div className="grid grid-cols-2 gap-3">
              <Input
                label="Row from"
                value={bulkForm.rowStart}
                onChange={(e) => setBulkForm({ ...bulkForm, rowStart: e.target.value })}
                placeholder="A or 1"
              />
              <Input
                label="Row to"
                value={bulkForm.rowEnd}
                onChange={(e) => setBulkForm({ ...bulkForm, rowEnd: e.target.value })}
                placeholder="D or 5"
              />
            </div>
          </div>

          {/* Seat range */}
          <div>
            <p className="text-xs font-medium text-gray-600 mb-1">Seat range *</p>
            <div className="grid grid-cols-2 gap-3">
              <Input
                label="Seat from *"
                type="number"
                min="1"
                value={bulkForm.seatStart}
                onChange={(e) => setBulkForm({ ...bulkForm, seatStart: e.target.value })}
                placeholder="1"
                required
              />
              <Input
                label="Seat to *"
                type="number"
                min="1"
                value={bulkForm.seatEnd}
                onChange={(e) => setBulkForm({ ...bulkForm, seatEnd: e.target.value })}
                placeholder="20"
                required
              />
            </div>
          </div>

          {/* Face price */}
          <Input
            label="Face price ($) *"
            type="number"
            min="0.01"
            step="0.01"
            value={bulkForm.facePrice}
            onChange={(e) => setBulkForm({ ...bulkForm, facePrice: e.target.value })}
            placeholder="50.00"
            required
          />

          {/* Success banner */}
          {bulkCreated !== null && (
            <div className="rounded-lg px-4 py-3 text-sm font-medium bg-green-50 text-green-700">
              ✓ Created {bulkCreated} ticket{bulkCreated !== 1 ? 's' : ''} successfully.
            </div>
          )}

          {/* Preview */}
          {bulkCreated === null && (
            <div
              className={`rounded-lg px-4 py-3 text-sm font-medium
                ${bulkPreview !== null && bulkPreview > 0
                  ? 'bg-blue-50 text-blue-700'
                  : 'bg-gray-50 text-gray-400'}`}
            >
              {bulkPreview === null
                ? 'Enter a valid seat range to see a preview.'
                : bulkPreview === 0
                ? 'Range is empty — check your values.'
                : bulkPreview > 2000
                ? `⚠ Range exceeds 2 000 tickets — reduce the span.`
                : `Will generate up to ${bulkPreview} ticket${bulkPreview !== 1 ? 's' : ''} (existing seats are skipped).`}
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}
