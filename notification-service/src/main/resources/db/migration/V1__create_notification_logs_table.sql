CREATE TABLE notification_logs (
    id            UUID          PRIMARY KEY,
    type          VARCHAR(30)   NOT NULL,
    recipient     VARCHAR(255)  NOT NULL,
    subject       VARCHAR(500),
    body          TEXT,
    reference_id  VARCHAR(36),
    status        VARCHAR(20)   NOT NULL DEFAULT 'SENT',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_recipient ON notification_logs (recipient);
CREATE INDEX idx_notification_type      ON notification_logs (type);
CREATE INDEX idx_notification_ref       ON notification_logs (reference_id);
