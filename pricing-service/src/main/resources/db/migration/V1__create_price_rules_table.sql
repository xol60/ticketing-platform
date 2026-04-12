-- V1: Create event_price_rules table for the pricing service

CREATE TABLE IF NOT EXISTS event_price_rules (
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id      VARCHAR(255) NOT NULL,
    event_name    VARCHAR(255),
    min_price     NUMERIC(10, 2) NOT NULL,
    max_price     NUMERIC(10, 2) NOT NULL,
    current_price NUMERIC(10, 2) NOT NULL,
    demand_factor DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    total_tickets INTEGER      NOT NULL DEFAULT 0,
    sold_tickets  INTEGER      NOT NULL DEFAULT 0,
    event_date    TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_event_price_rules_event_id UNIQUE (event_id),
    CONSTRAINT chk_price_range CHECK (min_price <= max_price),
    CONSTRAINT chk_current_in_range CHECK (current_price >= min_price AND current_price <= max_price),
    CONSTRAINT chk_sold_tickets CHECK (sold_tickets >= 0 AND sold_tickets <= total_tickets)
);

CREATE INDEX IF NOT EXISTS idx_event_price_rules_event_id ON event_price_rules (event_id);
