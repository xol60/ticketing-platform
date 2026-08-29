-- Event ownership for fine-grained EVENT_OWNER RBAC.
-- owner_id references the auth-service user id of the EVENT_OWNER (or ADMIN)
-- who created the event. Cross-DB, so no FK — validated at the application tier.
ALTER TABLE events ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_events_owner_id ON events (owner_id);

-- Backfill pre-existing events to the seeded admin so nothing is orphaned.
-- (Admin id is the fixed seed from auth-service V1__create_users_tables.sql.)
UPDATE events SET owner_id = 'admin-000-000-000-000000000001' WHERE owner_id IS NULL;
