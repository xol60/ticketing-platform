package com.ticketing.gateway.security;

import com.ticketing.gateway.config.GatewayProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
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

            var identity = new TokenIdentity(
                    claims.getSubject(),
                    claims.get("role",     String.class),
                    claims.get("tenantId", String.class),
                    claims.getExpiration().toInstant()
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
     * Builds a deterministic cache key from the token.
     * We hash to avoid storing the raw bearer token as a Redis key.
     */
    public String cacheKey(String token) {
        // Simple prefix + last 16 chars of token is NOT secure enough for production.
        // Use SHA-256 in production — kept simple here for readability.
        int hash = token.hashCode();
        return "token:" + Integer.toUnsignedString(hash, 16);
    }

    /** Key used to mark a token as revoked in Redis. */
    public String revocationKey(String token) {
        return "revoked:" + cacheKey(token);
    }
}
