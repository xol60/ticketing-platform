-- V3: Replace fixed min/max/current price columns with surge-multiplier model.
-- EventPriceRule entity now uses surgeMultiplier & maxSurge; effectivePrice = facePrice * surgeMultiplier.

-- 1. Drop check constraints that reference columns we are about to remove
ALTER TABLE event_price_rules
    DROP CONSTRAINT IF EXISTS chk_price_range,
    DROP CONSTRAINT IF EXISTS chk_current_in_range;

-- 2. Remove old fixed-price columns
ALTER TABLE event_price_rules
    DROP COLUMN IF EXISTS min_price,
    DROP COLUMN IF EXISTS max_price,
    DROP COLUMN IF EXISTS current_price;

-- 3. Add surge multiplier columns (with safe defaults so existing rows get 1.0 / 1.5)
ALTER TABLE event_price_rules
    ADD COLUMN IF NOT EXISTS surge_multiplier NUMERIC(6, 4) NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS max_surge        NUMERIC(6, 4) NOT NULL DEFAULT 1.5;
