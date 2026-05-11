-- V2: add a partial index that makes the watchdog query efficient at DB level.
-- ─────────────────────────────────────────────────────────────────────────────
-- The watchdog runs every 60 s and asks:
--   "Give me all active sagas whose updated_at is older than threshold X"
--
-- V1 had only idx_saga_states_active_status (partial on status).
-- That index lets Postgres find active-saga row IDs quickly, but the watchdog
-- had to fetch ALL active rows into Java and deserialise each JSON blob just to
-- check lastUpdatedAt — O(active_sagas) deserialisation per minute.
--
-- This index adds updated_at into the partial index so Postgres can apply the
-- time filter itself:
--   SELECT * FROM saga_states
--   WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')
--     AND updated_at < :threshold
--
-- Postgres performs an index range scan on updated_at within the partial slice
-- (only non-terminal rows), returning only sagas that are genuinely stuck.
-- Under normal load this result set is empty — the scan terminates immediately.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_saga_states_active_stale
    ON saga_states (updated_at)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- Keep the original status-only partial index: it is still used by the admin
-- API (findByStatusNotIn with pagination) which does not filter by updated_at.
-- idx_saga_states_active_status defined in V1 is intentionally left in place.
