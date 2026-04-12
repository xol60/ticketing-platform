CREATE TABLE listings (
    id                    VARCHAR(36)    PRIMARY KEY,
    ticket_id             VARCHAR(36)    NOT NULL,
    seller_id             VARCHAR(36)    NOT NULL,
    event_id              VARCHAR(36)    NOT NULL,
    ask_price             NUMERIC(10,2)  NOT NULL,
    status                VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    purchased_by_user_id  VARCHAR(36),
    purchased_order_id    VARCHAR(36),
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_listings_event_status ON listings (event_id, status);
CREATE INDEX idx_listings_seller       ON listings (seller_id);
CREATE INDEX idx_listings_ticket       ON listings (ticket_id);
