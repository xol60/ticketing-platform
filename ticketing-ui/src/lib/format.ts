/**
 * Shared price-display helpers.
 *
 * <p>We standardise on one money formatter across the app so a stale
 * legacy ticket priced at e.g. 1500000 doesn't render as "1500000.00" — it
 * renders as "$1,500,000.00" with thousands separators, which makes
 * mismatched-currency outliers obvious instead of looking like a UI bug.
 *
 * <p>Currency is hard-coded USD because the backend doesn't currently track
 * per-event currency. If you see a price that's clearly not USD-shaped
 * (e.g. 1,500,000) the underlying data is the issue — see the
 * "normalise legacy face prices" block in {@code tests/seed-demo-data.sql}
 * for the heuristic we use to push such rows back into demo range.
 */

const MONEY = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** "$1,234.56" — for any displayed ticket / order / payment amount. */
export function money(amount: number | null | undefined): string {
  if (amount == null || Number.isNaN(amount)) return '$—';
  return '$' + MONEY.format(amount);
}

/** "1.50×" — for surge multipliers. Always 2 decimals so 1 vs 1.00 line up. */
export function multiplier(x: number | null | undefined): string {
  if (x == null) return '—';
  return x.toFixed(2) + '×';
}
