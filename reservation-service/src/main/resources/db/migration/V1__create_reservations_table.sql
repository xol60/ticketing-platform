CREATE TABLE reservations (
    id           UUID            PRIMARY KEY,
    ticket_id    VARCHAR(36)     NOT NULL,
    user_id      VARCHAR(36)     NOT NULL,
    status       VARCHAR(20)     NOT NULL DEFAULT 'QUEUED',
    queued_at    TIMESTAMPTZ     NOT NULL,
    promoted_at  TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ     NOT NULL,
    version      BIGINT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reservations_ticket_status ON reservations (ticket_id, status);
CREATE INDEX idx_reservations_user_ticket   ON reservations (user_id, ticket_id);
CREATE INDEX idx_reservations_expires_at    ON reservations (expires_at) WHERE status = 'QUEUED';
