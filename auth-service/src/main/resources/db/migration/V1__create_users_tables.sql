-- ── Users ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id                      VARCHAR(36)     PRIMARY KEY,
    email                   VARCHAR(255)    NOT NULL UNIQUE,
    username                VARCHAR(100)    NOT NULL UNIQUE,
    password_hash           VARCHAR(255)    NOT NULL,
    role                    VARCHAR(20)     NOT NULL DEFAULT 'USER',
    tenant_id               VARCHAR(100),
    enabled                 BOOLEAN         NOT NULL DEFAULT TRUE,
    email_verified          BOOLEAN         NOT NULL DEFAULT FALSE,
    failed_login_attempts   INT             NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email    ON users (email);
CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);
CREATE INDEX IF NOT EXISTS idx_users_role     ON users (role);

-- ── Refresh tokens ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id           VARCHAR(36)   PRIMARY KEY,
    token_hash   VARCHAR(64)   NOT NULL UNIQUE,
    user_id      VARCHAR(36)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ   NOT NULL,
    revoked      BOOLEAN       NOT NULL DEFAULT FALSE,
    revoked_at   TIMESTAMPTZ,
    device_info  VARCHAR(255),
    ip_address   VARCHAR(45),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rt_token_hash    ON refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_rt_user_id       ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_rt_expires_at    ON refresh_tokens (expires_at);
CREATE INDEX IF NOT EXISTS idx_rt_revoked       ON refresh_tokens (revoked);

-- ── Seed: default admin user (password: Admin@1234) ──────────────────────────
-- BCrypt hash of "Admin@1234" with strength 12
INSERT INTO users (id, email, username, password_hash, role, enabled, email_verified)
VALUES (
    'admin-000-000-000-000000000001',
    'admin@ticketing.local',
    'admin',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/lewKyNiLXCJozFClm',
    'ADMIN',
    TRUE,
    TRUE
) ON CONFLICT DO NOTHING;
