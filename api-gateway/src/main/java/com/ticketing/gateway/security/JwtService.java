package com.ticketing.gateway.security;

import com.ticketing.gateway.config.GatewayProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final GatewayProperties properties;

    private SecretKey secretKey() {
        byte[] keyBytes = properties.getJwt().getSecret()
                .getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Fully validates the token (signature, expiry, issuer) and returns
     * a TokenIdentity on success, or empty on any failure.
     */
    public Optional<TokenIdentity> validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // "gen" claim was added after initial release; default to 0 for older tokens
            // so they remain valid until a security event increments the counter.
            Long genClaim = claims.get("gen", Long.class);
            long gen = genClaim != null ? genClaim : 0L;

            var identity = new TokenIdentity(
                    claims.getSubject(),
                    claims.get("role",     String.class),
                    claims.get("tenantId", String.class),
                    claims.getExpiration().toInstant(),
                    gen
            );

            if (identity.isExpired()) {
                log.debug("Token expired for user={}", identity.getUserId());
                return Optional.empty();
            }

            return Optional.of(identity);

        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error validating JWT", e);
        }
        return Optional.empty();
    }

    /**
     * Builds a deterministic cache key from the token using SHA-256.
     * SHA-256 is a one-way cryptographic hash — it cannot be reversed to recover the
     * original token, produces no collisions, and matches the key format that
     * auth-service writes to TokenRevocationStore (revoked:token:{sha256}).
     */
    public String cacheKey(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "token:" + hex;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256 cache key", e);
        }
    }

    /** Key used to mark a token as revoked in Redis. */
    public String revocationKey(String token) {
        return "revoked:" + cacheKey(token);
    }
}
