-- V2: Prevent a ticket from having more than one ACTIVE listing at a time.
--
-- Problem: two concurrent POST /listings requests for the same ticketId could
-- both pass the application-level "already listed?" check before either
-- commits, resulting in duplicate active listings for the same ticket.
--
-- Fix: partial unique index on (ticket_id) WHERE status = 'ACTIVE'.
-- A ticket can have multiple historical (SOLD / CANCELLED) listings, but only
-- one active listing at any given time. The DB enforces this atomically.

CREATE UNIQUE INDEX idx_listings_one_active_per_ticket
    ON listings (ticket_id)
    WHERE status = 'ACTIVE';
