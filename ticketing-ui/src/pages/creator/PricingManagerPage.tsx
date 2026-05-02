import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { pricingApi } from '../../api/pricing';
import { Card, CardHeader, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { PageSpinner } from '../../components/ui/Spinner';

export function PricingManagerPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const queryClient = useQueryClient();

  const { data: events = [] } = useQuery({ queryKey: ['events'], queryFn: ticketsApi.listEvents });
  const { data: tickets = [] } = useQuery({
    queryKey: ['tickets', eventId],
    queryFn:  () => ticketsApi.listByEvent(eventId!),
    enabled:  !!eventId,
  });
  const { data: rule, isLoading, isError } = useQuery({
    queryKey: ['pricing-rule', eventId],
    queryFn: () => pricingApi.getRule(eventId!),
    enabled: !!eventId,
    retry: false,
  });

  const [form, setForm] = useState({ surgeMultiplier: '1.0', maxSurge: '1.5' });
  const [saved, setSaved] = useState(false);

  const event = events.find((e) => e.id === eventId);

  const createMut = useMutation({
    mutationFn: () => pricingApi.createRule({
      eventId: eventId!,
      surgeMultiplier: parseFloat(form.surgeMultiplier),
      maxSurge: parseFloat(form.maxSurge),
    }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['pricing-rule', eventId] }); setSaved(true); setTimeout(() => setSaved(false), 2000); },
  });

  const updateMut = useMutation({
    mutationFn: () => pricingApi.updateRule(eventId!, {
      surgeMultiplier: parseFloat(form.surgeMultiplier),
      maxSurge: parseFloat(form.maxSurge),
    }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['pricing-rule', eventId] }); setSaved(true); setTimeout(() => setSaved(false), 2000); },
  });

  // Seed form when rule loads
  const ruleRef = rule?.id;
  if (rule && form.surgeMultiplier === '1.0' && ruleRef) {
    setForm({ surgeMultiplier: String(rule.surgeMultiplier), maxSurge: String(rule.maxSurge) });
  }

  const demandPct = rule ? Math.round(rule.demandFactor * 100) : 0;
  const effectivePrice = rule && tickets[0]
    ? (tickets[0].facePrice * rule.surgeMultiplier).toFixed(2)
    : '—';

  return (
    <div className="max-w-xl mx-auto flex flex-col gap-6">
      <nav className="text-sm text-gray-400 flex items-center gap-2">
        <Link to="/creator" className="hover:text-blue-600 transition-colors">My Events</Link>
        <span>/</span>
        <span className="text-gray-700 font-medium">{event?.name ?? 'Event'}</span>
        <span>/</span>
        <span className="text-gray-700">Pricing</span>
      </nav>

      <h1 className="text-xl font-bold text-gray-900">Dynamic Pricing</h1>

      {isLoading && <PageSpinner />}

      {/* Live stats */}
      {rule && (
        <div className="grid grid-cols-3 gap-4">
          {[
            { label: 'Demand', value: `${demandPct}%`, color: demandPct > 70 ? 'text-orange-600' : 'text-gray-900' },
            { label: 'Sold / Total', value: `${rule.soldTickets} / ${rule.totalTickets}` , color: 'text-gray-900' },
            { label: 'Current surge', value: `×${rule.surgeMultiplier.toFixed(2)}`, color: rule.surgeMultiplier > 1 ? 'text-orange-600' : 'text-green-600' },
          ].map((s) => (
            <div key={s.label} className="bg-white rounded-2xl border border-gray-100 shadow-sm px-4 py-4 text-center">
              <p className={`text-2xl font-bold ${s.color}`}>{s.value}</p>
              <p className="text-xs text-gray-400 mt-1">{s.label}</p>
            </div>
          ))}
        </div>
      )}

      {/* Demand bar */}
      {rule && (
        <Card>
          <CardHeader title="Demand meter" />
          <CardBody>
            <div className="w-full bg-gray-100 rounded-full h-3 overflow-hidden">
              <div
                className={`h-3 rounded-full transition-all duration-500 ${demandPct > 80 ? 'bg-red-500' : demandPct > 50 ? 'bg-orange-400' : 'bg-green-500'}`}
                style={{ width: `${demandPct}%` }}
              />
            </div>
            <p className="text-xs text-gray-400 mt-2">
              {demandPct}% of tickets sold or reserved.
              {tickets[0] && ` Estimated effective price: $${effectivePrice}`}
            </p>
          </CardBody>
        </Card>
      )}

      {/* Price rule form */}
      <Card>
        <CardHeader
          title={rule ? 'Update pricing rule' : 'Set pricing rule'}
          subtitle="Control how the price surges with demand"
        />
        <CardBody className="flex flex-col gap-5">
          {isError && !rule && (
            <div className="bg-blue-50 border border-blue-200 text-blue-700 text-sm rounded-lg px-4 py-2.5">
              No pricing rule set yet. Create one below.
            </div>
          )}

          <div>
            <Input
              label="Initial surge multiplier (e.g. 1.0 = no surge, 1.2 = 20% above face)"
              type="number" min="1" max="5" step="0.01"
              value={form.surgeMultiplier}
              onChange={(e) => setForm({ ...form, surgeMultiplier: e.target.value })}
            />
            <p className="text-xs text-gray-400 mt-1">
              Effective price = face price × multiplier
            </p>
          </div>

          <div>
            <Input
              label="Max surge cap (e.g. 1.5 = price can at most be 50% above face)"
              type="number" min="1" max="5" step="0.01"
              value={form.maxSurge}
              onChange={(e) => setForm({ ...form, maxSurge: e.target.value })}
            />
            <p className="text-xs text-gray-400 mt-1">
              Protects buyers from excessive price hikes
            </p>
          </div>

          <div className="flex items-center gap-3">
            <Button
              loading={createMut.isPending || updateMut.isPending}
              onClick={() => rule ? updateMut.mutate() : createMut.mutate()}
            >
              {rule ? 'Update rule' : 'Create rule'}
            </Button>
            {saved && <span className="text-green-600 text-sm font-medium">✓ Saved!</span>}
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
