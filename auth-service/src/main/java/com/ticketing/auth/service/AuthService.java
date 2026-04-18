package com.ticketing.auth.service;

import com.ticketing.auth.config.AuthProperties;
import com.ticketing.auth.domain.model.RefreshToken;
import com.ticketing.auth.domain.model.User;
import com.ticketing.auth.domain.repository.RefreshTokenRepository;
import com.ticketing.auth.domain.repository.UserRepository;
import com.ticketing.auth.dto.request.LoginRequest;
import com.ticketing.auth.dto.request.RefreshRequest;
import com.ticketing.auth.dto.request.RegisterRequest;
import com.ticketing.auth.dto.response.AuthResponse;
import com.ticketing.auth.dto.response.UserResponse;
import com.ticketing.auth.exception.AuthException;
import com.ticketing.auth.security.JwtTokenService;
import com.ticketing.auth.security.TokenRevocationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository         userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService        jwtTokenService;
    private final TokenRevocationStore   revocationStore;
    private final PasswordEncoder        passwordEncoder;
    private final AuthProperties         properties;

    // ── Register ──────────────────────────────────────────────────────────────

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw AuthException.emailTaken();
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw AuthException.usernameTaken();
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase().trim())
                .username(request.getUsername().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.USER)
                .tenantId(request.getTenantId())
                .enabled(true)
                .emailVerified(false)
                .build();

        userRepository.save(user);
        log.info("User registered id={} email={}", user.getId(), user.getEmail());

        return toUserResponse(user);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String deviceInfo) {
        // Find by email or username
        User user = userRepository.findByEmail(request.getEmailOrUsername())
                .or(() -> userRepository.findByUsername(request.getEmailOrUsername()))
                .orElseThrow(AuthException::invalidCredentials);

        if (!user.isEnabled()) {
            throw new AuthException("ACCOUNT_DISABLED", "Account is disabled");
        }
        if (user.isAccountLocked()) {
            throw AuthException.accountLocked(0);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.recordFailedLogin();
            userRepository.save(user);
            throw AuthException.invalidCredentials();
        }

        user.recordSuccessfulLogin();
        userRepository.save(user);

        return issueTokenPair(user, ipAddress, deviceInfo);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refresh(RefreshRequest request, String ipAddress, String deviceInfo) {
        String tokenHash = jwtTokenService.hashRefreshToken(request.getRefreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> AuthException.tokenInvalid("Refresh token not found"));

        if (!stored.isValid()) {
            // Potential token reuse — revoke all tokens for this user (security measure)
            if (stored.isRevoked()) {
                log.warn("Refresh token reuse detected for userId={}", stored.getUser().getId());
                refreshTokenRepository.revokeAllForUser(stored.getUser().getId(), Instant.now());
            }
            throw AuthException.tokenInvalid("Refresh token is invalid or expired");
        }

        // Rotate: revoke old token, issue new pair
        stored.revoke();
        refreshTokenRepository.save(stored);

        User user = stored.getUser();
        log.info("Token refreshed userId={}", user.getId());

        return issueTokenPair(user, ipAddress, deviceInfo);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Transactional
    public void logout(String userId, String accessToken, String refreshToken) {
        // Revoke access token in Redis (API Gateway checks this on cold path)
        if (accessToken != null) {
            String hash = jwtTokenService.hashAccessToken(accessToken);
            revocationStore.revoke(hash, jwtTokenService.accessTokenExpirySeconds());
        }

        // Revoke specific refresh token if provided
        if (refreshToken != null) {
            String hash = jwtTokenService.hashRefreshToken(refreshToken);
            refreshTokenRepository.findByTokenHash(hash)
                    .ifPresent(rt -> {
                        rt.revoke();
                        refreshTokenRepository.save(rt);
                    });
        }

        log.info("User logged out userId={}", userId);
    }

    @Transactional
    public void logoutAll(String userId) {
        // Revoke all refresh tokens for this user
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("Revoked {} refresh tokens for userId={}", revoked, userId);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UserResponse getUser(String userId) {
        return userRepository.findActiveById(userId)
                .map(this::toUserResponse)
                .orElseThrow(() -> AuthException.tokenInvalid("User not found"));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AuthResponse issueTokenPair(User user, String ipAddress, String deviceInfo) {
        // Enforce max active refresh tokens per user — evict oldest
        evictOldestIfNeeded(user);

        String rawRefreshToken = jwtTokenService.generateRefreshToken();
        String accessToken     = jwtTokenService.generateAccessToken(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .tokenHash(jwtTokenService.hashRefreshToken(rawRefreshToken))
                .user(user)
                .expiresAt(Instant.now().plusSeconds(
                        jwtTokenService.refreshTokenExpirySeconds()))
                .ipAddress(ipAddress)
                .deviceInfo(deviceInfo)
                .revoked(false)
                .build();

        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .accessTokenExpiresIn(jwtTokenService.accessTokenExpirySeconds())
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole().name())
                .build();
    }

    private void evictOldestIfNeeded(User user) {
        int max = properties.getToken().getMaxRefreshTokensPerUser();
        List<RefreshToken> active = refreshTokenRepository
                .findByUserIdOrderByCreatedAtAsc(user.getId());

        if (active.size() >= max) {
            // Revoke oldest tokens until we're under the limit
            active.stream()
                  .limit(active.size() - max + 1)
                  .forEach(rt -> {
                      rt.revoke();
                      refreshTokenRepository.save(rt);
                  });
        }
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .role(user.getRole().name())
                .tenantId(user.getTenantId())
                .enabled(user.isEnabled())
                .emailVerified(user.isEmailVerified())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
