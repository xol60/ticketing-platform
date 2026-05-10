-- Add the promote_expires_at column used by the stale-promotion watchdog.
-- Nullable because it is only set when a reservation transitions to PROMOTED.
ALTER TABLE reservations
    ADD COLUMN promote_expires_at TIMESTAMPTZ;

-- Partial index: only PROMOTED rows are scanned by advanceStalePromotions().
CREATE INDEX idx_reservations_promote_expires
    ON reservations (promote_expires_at)
    WHERE status = 'PROMOTED';
