type Variant = 'green' | 'blue' | 'yellow' | 'red' | 'gray' | 'purple';

const variantClasses: Record<Variant, string> = {
  green:  'bg-green-100 text-green-800',
  blue:   'bg-blue-100 text-blue-800',
  yellow: 'bg-yellow-100 text-yellow-800',
  red:    'bg-red-100 text-red-800',
  gray:   'bg-gray-100 text-gray-700',
  purple: 'bg-purple-100 text-purple-800',
};

export function Badge({ label, variant = 'gray' }: { label: string; variant?: Variant }) {
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${variantClasses[variant]}`}>
      {label}
    </span>
  );
}

// ── Status badge helpers ──────────────────────────────────────────────────────
export function OrderStatusBadge({ status }: { status: string }) {
  const map: Record<string, [string, Variant]> = {
    PENDING:       ['Pending',        'yellow'],
    PRICE_CHANGED: ['Price Changed',  'purple'],
    CONFIRMED:     ['Confirmed',      'green'],
    FAILED:        ['Failed',         'red'],
    CANCELLED:     ['Cancelled',      'gray'],
  };
  const [label, variant] = map[status] ?? [status, 'gray'];
  return <Badge label={label} variant={variant} />;
}

export function EventStatusBadge({ status }: { status: string }) {
  const map: Record<string, [string, Variant]> = {
    DRAFT:     ['Draft',     'gray'],
    OPEN:      ['Open',      'green'],
    CLOSED:    ['Closed',    'blue'],
    CANCELLED: ['Cancelled', 'red'],
  };
  const [label, variant] = map[status] ?? [status, 'gray'];
  return <Badge label={label} variant={variant} />;
}

export function TicketStatusBadge({ status }: { status: string }) {
  const map: Record<string, [string, Variant]> = {
    AVAILABLE: ['Available', 'green'],
    RESERVED:  ['Reserved',  'yellow'],
    CONFIRMED: ['Sold',      'gray'],
  };
  const [label, variant] = map[status] ?? [status, 'gray'];
  return <Badge label={label} variant={variant} />;
}
