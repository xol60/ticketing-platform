-- V1__create_orders_table.sql
-- Orders table for the order-service

CREATE TABLE IF NOT EXISTS orders (
    id                VARCHAR(36)    NOT NULL,
    user_id           VARCHAR(36)    NOT NULL,
    ticket_id         VARCHAR(36)    NOT NULL,
    saga_id           VARCHAR(36)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    requested_price   NUMERIC(19, 4) NOT NULL,
    final_price       NUMERIC(19, 4),
    payment_reference VARCHAR(255),
    failure_reason    TEXT,
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id  ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_orders_ticket_id ON orders (ticket_id);
CREATE INDEX IF NOT EXISTS idx_orders_saga_id   ON orders (saga_id);
CREATE INDEX IF NOT EXISTS idx_orders_status    ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders (created_at DESC);
