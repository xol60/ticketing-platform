# Auth Service

**Port:** 8081

Manages user identity: registration, login, JWT issuance (access + refresh tokens), token revocation, and logout. All other services trust the `X-User-Id` / `X-User-Role` headers injected by the API Gateway — they never re-validate tokens themselves.

---

## Responsibilities

- User registration and credential storage (bcrypt hashed passwords)
- Login / logout / refresh token flows
- JWT (HMAC-SHA256) issuance with short-lived access tokens and long-lived refresh tokens
- Token revocation via Redis (blacklist by `jti`)
- Device and IP tracking per session

---

## Internal Architecture

```
AuthController
  → AuthService
      ├── Register: hash password → save user → issue tokens
      ├── Login:    verify password → check account state → issue tokens → store session in Redis
      ├── Refresh:  validate refresh token → revoke old → issue new pair
      └── Logout:   revoke access + refresh tokens in Redis
```

### Token design

| Token | TTL | Storage |
|-------|-----|---------|
| Access token (JWT) | 15 minutes | Client only |
| Refresh token (JWT) | 7 days | Client + Redis (for revocation) |

JWT payload: `sub` (userId), `role`, `jti` (unique ID), `iat`, `exp`.

The gateway validates access tokens by checking:
1. Signature (shared `JWT_SECRET`)
2. Expiry
3. Redis revocation set (`revoked:{jti}`)

### Master/slave consistency

After login or registration, a 5-second "sticky master" window forces reads for that user to go to the master DB. This prevents a replication lag window where a freshly registered user would appear not to exist. Implemented via `DataSourceRoutingContext` + `ReplicationConsistencyAspect`.

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/register` | No | Create new user account |
| `POST` | `/api/auth/login` | No | Login, returns access + refresh tokens |
| `POST` | `/api/auth/refresh` | No | Exchange refresh token for new pair |
| `POST` | `/api/auth/logout` | Yes (access token) | Revoke current session |
| `POST` | `/api/auth/logout-all` | Yes | Revoke all sessions for this user |
| `GET` | `/api/auth/me` | Yes | Get current user profile |

---

## Normal Flow — Login

```
POST /api/auth/login { email, password }
  → Load user from DB (master)
  → Verify bcrypt password
  → Generate access token (15m) + refresh token (7d)
  → Store refresh token in Redis with TTL
  → Return { accessToken, refreshToken, userId, role }
```

## Normal Flow — Refresh

```
POST /api/auth/refresh { refreshToken }
  → Validate refresh token signature + expiry
  → Check token not revoked in Redis
  → Revoke old refresh token in Redis
  → Issue new access + refresh token pair
  → Return new { accessToken, refreshToken }
```

---

## Failure Flows

| Scenario | Response |
|----------|----------|
| Email already registered | `409 Conflict` |
| Wrong password | `401 Unauthorized` |
| Expired access token | `401 Unauthorized` (client should refresh) |
| Expired or revoked refresh token | `401 Unauthorized` (user must log in again) |
| Account not found | `404 Not Found` |

---

## Dependencies

- **PostgreSQL** — user accounts (`auth_db`)
- **Redis** — refresh token storage, access token revocation set (`revoked:{jti}`)
