-- V2: add pending_price for PRICE_CHANGED state and expand status constraint

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS pending_price NUMERIC(19, 4);

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS chk_orders_status;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status
        CHECK (status IN ('PENDING', 'PRICE_CHANGED', 'CONFIRMED', 'FAILED', 'CANCELLED'));
