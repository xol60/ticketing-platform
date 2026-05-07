-- Add explicit reservation deadline column.
-- The watchdog uses this instead of the age-based (reserved_at < now - threshold) check,
-- so a ticket is never released while its saga still has budget left to complete.
ALTER TABLE tickets ADD COLUMN reserved_until TIMESTAMP WITH TIME ZONE;

-- Index so the watchdog query (WHERE status='RESERVED' AND reserved_until < now()) is fast.
CREATE INDEX idx_tickets_reserved_until ON tickets (reserved_until)
    WHERE status = 'RESERVED';
