CREATE TABLE tickets (
    id                  VARCHAR(36)     PRIMARY KEY,
    event_id            VARCHAR(36)     NOT NULL,
    event_name          VARCHAR(255)    NOT NULL,
    section             VARCHAR(100),
    row                 VARCHAR(20),
    seat                VARCHAR(20)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE',
    face_price          NUMERIC(10, 2)  NOT NULL,
    locked_price        NUMERIC(10, 2),
    locked_by_order_id  VARCHAR(36),
    locked_by_user_id   VARCHAR(36),
    reserved_at         TIMESTAMPTZ,
    confirmed_at        TIMESTAMPTZ,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tickets_event_id    ON tickets (event_id);
CREATE INDEX idx_tickets_status      ON tickets (status);
CREATE INDEX idx_tickets_event_status ON tickets (event_id, status);
CREATE INDEX idx_tickets_order_id    ON tickets (locked_by_order_id);

-- Unique constraint: one physical seat per event
CREATE UNIQUE INDEX idx_tickets_unique_seat
    ON tickets (event_id, section, row, seat);
