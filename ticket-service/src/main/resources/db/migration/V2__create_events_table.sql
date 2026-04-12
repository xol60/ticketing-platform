CREATE TABLE events (
    id             VARCHAR(36)  PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    sales_open_at  TIMESTAMPTZ  NOT NULL,
    sales_close_at TIMESTAMPTZ  NOT NULL,
    event_date     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_status ON events (status);
