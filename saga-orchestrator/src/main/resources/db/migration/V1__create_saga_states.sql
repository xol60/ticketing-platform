CREATE TABLE IF NOT EXISTS saga_states (
    saga_id    VARCHAR(36)  NOT NULL,
    status     VARCHAR(30)  NOT NULL,
    state_json TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_saga_states PRIMARY KEY (saga_id)
);

-- Partial index: watchdog only ever queries non-terminal sagas
CREATE INDEX IF NOT EXISTS idx_saga_states_active_status
    ON saga_states(status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');
