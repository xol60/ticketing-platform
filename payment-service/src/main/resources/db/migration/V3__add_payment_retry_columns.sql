-- V3: add retry-scheduling columns needed by the PaymentRetryWatchdog.
--
-- Previously the retry loop ran inside the Kafka consumer thread (Thread.sleep),
-- freezing the entire partition for up to 7 seconds per failed charge.
-- The watchdog pattern moves retries to a dedicated scheduler thread:
--   1. Consumer thread: save PENDING + next_retry_at = now(), return immediately.
--   2. Watchdog (@Scheduled every 2 s): query PENDING rows due for retry,
--      call gateway.charge() outside any DB transaction, update status.
-- ─────────────────────────────────────────────────────────────────────────────

-- saga_id: required so the watchdog can publish PaymentSucceededEvent /
--          PaymentFailedEvent with the correct sagaId that the saga-orchestrator
--          uses to correlate the event back to the running saga.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS saga_id  VARCHAR(36);

-- trace_id: propagated for distributed tracing; nullable (not all paths set it).
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

-- next_retry_at: when the watchdog should next attempt a charge for this payment.
--   - Set to NOW() when a PaymentChargeCommand arrives (= "retry immediately").
--   - Updated to NOW() + backoff after each failed attempt.
--   - Set to NULL when the payment reaches a terminal status (SUCCESS/FAILED/REFUNDED).
--   - Also used as a 30-second "claim lease": the watchdog sets next_retry_at = now+30s
--     before calling the gateway so that concurrent watchdog pods don't race.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

-- Partial index: only PENDING rows with a due retry_at are relevant.
-- The watchdog query is: WHERE status = 'PENDING' AND next_retry_at <= :now
-- This index is tiny (only in-flight payments are PENDING) and makes the
-- watchdog poll essentially free under normal load.
CREATE INDEX IF NOT EXISTS idx_payments_retry_due
    ON payments (next_retry_at)
    WHERE status = 'PENDING';
