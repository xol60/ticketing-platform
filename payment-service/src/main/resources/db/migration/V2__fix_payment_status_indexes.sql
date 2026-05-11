-- V2: fix CHECK constraint and add targeted partial indexes
-- ─────────────────────────────────────────────────────────────────────────────
-- BUG FIX: V1 CHECK only allowed PENDING/SUCCESS/FAILED.
-- PaymentService also writes CANCELLATION_REQUESTED and REFUNDED, which would
-- throw a constraint-violation the first time a saga cancels an in-flight charge.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS chk_payment_status;

ALTER TABLE payments
    ADD CONSTRAINT chk_payment_status
        CHECK (status IN (
            'PENDING',
            'SUCCESS',
            'FAILED',
            'CANCELLATION_REQUESTED',
            'REFUNDED'
        ));

-- ─────────────────────────────────────────────────────────────────────────────
-- Replace the broad idx_payments_status with targeted partial indexes.
--
-- PENDING   → retry scheduler + cancelPayment() idempotency check.
--             Small set (only in-flight payments); partial index is tiny and fast.
-- SUCCESS   → admin queries / audit.  Partial index avoids scanning FAILED rows.
--
-- FAILED / CANCELLATION_REQUESTED / REFUNDED are terminal or transitional and
-- are only accessed by orderId (already covered by the existing unique index
-- on order_id), so they don't need their own status index.
-- ─────────────────────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_payments_status;

-- Partial index: only PENDING rows — tiny, perfectly matches retry scheduler
CREATE INDEX idx_payments_pending
    ON payments (created_at)
    WHERE status = 'PENDING';

-- Partial index: only SUCCESS rows — used by admin reports / refund lookups
CREATE INDEX idx_payments_success
    ON payments (created_at DESC)
    WHERE status = 'SUCCESS';
