/**
 * Idempotency-key helpers for client → server POST requests.
 *
 * <p>Pairs with the {@code IdempotencyFilter} in common-lib on the server
 * side. The contract is "Stripe-style":
 *
 * <ul>
 *   <li>Each fresh user intent gets a fresh UUIDv4.</li>
 *   <li>Retries of the same intent reuse the same UUID (handled either by
 *       React Query auto-retry within a {@code useMutation}, or by the
 *       caller passing the same key explicitly).</li>
 *   <li>The server replies with the cached response if the same
 *       (userId, key) pair arrives again within the TTL, or 422
 *       {@code IDEMPOTENCY_KEY_REUSED} if the same key arrives with a
 *       different body.</li>
 * </ul>
 *
 * <p>This module deliberately stays tiny — just UUID generation + a header
 * builder. We intentionally do NOT persist keys to localStorage for now;
 * the in-memory mutation lifetime is enough to dedupe network retries,
 * and the server-side intent-window dedup (when/if we add it) covers
 * panic-clicks across page reloads.
 */

const HEADER = 'Idempotency-Key';

/**
 * Generate a fresh UUIDv4. Prefers the native {@code crypto.randomUUID()}
 * (available in all modern browsers + Node 19+); falls back to a tiny
 * polyfill so this file doesn't break on older targets or unit tests.
 */
export function newIdempotencyKey(): string {
  // crypto.randomUUID is widely available but `typeof` guards keep TS happy
  // and avoid throwing in stale environments.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Fallback: RFC4122-ish v4 from Math.random. Acceptable because the
  // server enforces per-user scope, so collision risk is harmless.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/**
 * Build the headers object axios accepts for a single request. Pass either
 * a key you generated earlier (so retries reuse the same one) or call
 * {@link newIdempotencyKey} inline for a fresh one.
 */
export function idempotencyHeaders(key: string): Record<string, string> {
  return { [HEADER]: key };
}
