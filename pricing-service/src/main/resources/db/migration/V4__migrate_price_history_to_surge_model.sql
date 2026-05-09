-- V4: Replace the absolute `price` column in price_history with `surge_multiplier`.
-- PriceHistory entity stores multipliers (not absolute prices) for point-in-time lookups.

-- 1. Drop old absolute price column
ALTER TABLE price_history
    DROP COLUMN IF EXISTS price;

-- 2. Add surge_multiplier column (1.0 default keeps existing rows valid)
ALTER TABLE price_history
    ADD COLUMN IF NOT EXISTS surge_multiplier NUMERIC(6, 4) NOT NULL DEFAULT 1.0;
