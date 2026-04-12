package com.ticketing.auth.security;

import com.ticketing.auth.config.AuthProperties;
import com.ticketing.auth.domain.model.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final AuthProperties properties;

    // ── Token generation ──────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        var jwt = properties.getJwt();
        Instant now    = Instant.now();
        Instant expiry = now.plusSeconds(jwt.getAccessTokenExpirySeconds());

        return Jwts.builder()
                .subject(user.getId())
                .issuer(jwt.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("role",     user.getRole().name())
                .claim("email",    user.getEmail())
                .claim("username", user.getUsername())
                .claim("tenantId", user.getTenantId())
                .signWith(secretKey())
                .compact();
    }

    /**
     * Generates an opaque, high-entropy refresh token.
     * The raw token is returned to the client; only its SHA-256 hash is stored in DB.
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ── Token validation ──────────────────────────────────────────────────────

    public Optional<Claims> validateAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (ExpiredJwtException e) {
            log.debug("Access token expired");
        } catch (JwtException e) {
            log.warn("Access token invalid: {}", e.getMessage());
        }
        return Optional.empty();
    }

    // ── Hashing ───────────────────────────────────────────────────────────────

    /** SHA-256 hash of the raw refresh token — for DB storage and lookup. */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash refresh token", e);
        }
    }

    /** Same hash used by API Gateway for the access token cache key. */
    public String hashAccessToken(String rawToken) {
        return hashRefreshToken(rawToken); // same SHA-256 logic
    }

    public long accessTokenExpirySeconds() {
        return properties.getJwt().getAccessTokenExpirySeconds();
    }

    public long refreshTokenExpirySeconds() {
        return properties.getJwt().getRefreshTokenExpirySeconds();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private SecretKey secretKey() {
        byte[] keyBytes = properties.getJwt().getSecret()
                .getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
