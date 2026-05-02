-- V3: Fix unique seat constraint to handle NULL section/row correctly.
--
-- Problem: PostgreSQL treats NULLs as distinct in standard unique indexes,
-- so (event1, NULL, NULL, 'seat1') can be inserted twice without a violation.
-- This allows duplicate general-admission tickets (no section / no row).
--
-- Fix: drop the old index and replace with NULLS NOT DISTINCT (PG 15+),
-- which treats all NULLs as equal for uniqueness purposes.
-- Fallback for PG < 15: use COALESCE to map NULL → '' before comparing.

-- Drop old index
DROP INDEX IF EXISTS idx_tickets_unique_seat;

-- Re-create with NULLS NOT DISTINCT (requires PostgreSQL 15+).
-- If your cluster runs PG < 15, replace with:
--   CREATE UNIQUE INDEX idx_tickets_unique_seat
--       ON tickets (event_id, COALESCE(section, ''), COALESCE(row, ''), seat);
CREATE UNIQUE INDEX idx_tickets_unique_seat
    ON tickets (event_id, section, row, seat)
    NULLS NOT DISTINCT;
