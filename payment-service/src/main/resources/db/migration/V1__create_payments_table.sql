CREATE TABLE IF NOT EXISTS payments (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    order_id         VARCHAR(255) NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    ticket_id        VARCHAR(255) NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    payment_reference VARCHAR(255),
    failure_reason   VARCHAR(512),
    attempt_count    INT          NOT NULL DEFAULT 0,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_payments         PRIMARY KEY (id),
    CONSTRAINT uq_payments_order   UNIQUE (order_id),
    CONSTRAINT chk_payment_status  CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payments (order_id);
CREATE INDEX IF NOT EXISTS idx_payments_user_id  ON payments (user_id);
CREATE INDEX IF NOT EXISTS idx_payments_status   ON payments (status);
