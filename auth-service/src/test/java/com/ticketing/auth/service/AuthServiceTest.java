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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock UserRepository         userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock JwtTokenService        jwtTokenService;
    @Mock TokenRevocationStore   revocationStore;
    @Mock PasswordEncoder        passwordEncoder;
    @Mock AuthProperties         properties;

    @InjectMocks AuthService authService;

    private AuthProperties.Token tokenProps;

    @BeforeEach
    void setUp() {
        tokenProps = new AuthProperties.Token();
        tokenProps.setMaxRefreshTokensPerUser(5);
        when(properties.getToken()).thenReturn(tokenProps);
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("creates user when email and username are unique")
        void register_success() {
            var req = new RegisterRequest();
            req.setEmail("alice@example.com");
            req.setUsername("alice");
            req.setPassword("Password@1234");

            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.prePersist();
                return u;
            });

            UserResponse response = authService.register(req);

            assertThat(response.getEmail()).isEqualTo("alice@example.com");
            assertThat(response.getUsername()).isEqualTo("alice");
            assertThat(response.getRole()).isEqualTo("USER");
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("throws when email already taken")
        void register_emailTaken() {
            var req = new RegisterRequest();
            req.setEmail("taken@example.com");
            req.setUsername("newuser");
            req.setPassword("Password@1234");

            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(req))
                    .isInstanceOf(AuthException.class)
                    .hasMessageContaining("Email already registered");
        }

        @Test
        @DisplayName("throws when username already taken")
        void register_usernameTaken() {
            var req = new RegisterRequest();
            req.setEmail("new@example.com");
            req.setUsername("takenuser");
            req.setPassword("Password@1234");

            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.existsByUsername("takenuser")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(req))
                    .isInstanceOf(AuthException.class)
                    .hasMessageContaining("Username already taken");
        }
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login()")
    class Login {

        private User activeUser;

        @BeforeEach
        void setUp() {
            activeUser = User.builder()
                    .id("user-001")
                    .email("alice@example.com")
                    .username("alice")
                    .passwordHash("hashed")
                    .role(User.Role.USER)
                    .enabled(true)
                    .failedLoginAttempts(0)
                    .build();
        }

        @Test
        @DisplayName("returns token pair on valid credentials")
        void login_success() {
            var req = new LoginRequest();
            req.setEmailOrUsername("alice@example.com");
            req.setPassword("Password@1234");

            when(userRepository.findByEmail("alice@example.com"))
                    .thenReturn(Optional.of(activeUser));
            when(passwordEncoder.matches("Password@1234", "hashed")).thenReturn(true);
            when(jwtTokenService.generateAccessToken(eq(activeUser), anyLong())).thenReturn("access.token");
            when(jwtTokenService.generateRefreshToken()).thenReturn("raw-refresh-token");
            when(jwtTokenService.hashRefreshToken("raw-refresh-token")).thenReturn("hashed-rt");
            when(jwtTokenService.refreshTokenExpirySeconds()).thenReturn(604800L);
            when(refreshTokenRepository.findByUserIdOrderByCreatedAtAsc(anyString()))
                    .thenReturn(List.of());
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AuthResponse response = authService.login(req, "127.0.0.1", "TestAgent");

            assertThat(response.getAccessToken()).isEqualTo("access.token");
            assertThat(response.getRefreshToken()).isEqualTo("raw-refresh-token");
            assertThat(response.getUserId()).isEqualTo("user-001");
            assertThat(response.getTokenType()).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("throws on wrong password and increments failed attempts")
        void login_wrongPassword() {
            var req = new LoginRequest();
            req.setEmailOrUsername("alice@example.com");
            req.setPassword("wrong");

            when(userRepository.findByEmail("alice@example.com"))
                    .thenReturn(Optional.of(activeUser));
            when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> authService.login(req, "127.0.0.1", "agent"))
                    .isInstanceOf(AuthException.class)
                    .hasMessageContaining("Invalid");

            assertThat(activeUser.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("throws when account is locked")
        void login_accountLocked() {
            activeUser.setLockedUntil(Instant.now().plusSeconds(300));
            var req = new LoginRequest();
            req.setEmailOrUsername("alice@example.com");
            req.setPassword("Password@1234");

            when(userRepository.findByEmail("alice@example.com"))
                    .thenReturn(Optional.of(activeUser));

            assertThatThrownBy(() -> authService.login(req, "127.0.0.1", "agent"))
                    .isInstanceOf(AuthException.class)
                    .hasMessageContaining("locked");
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refresh()")
    class Refresh {

        @Test
        @DisplayName("rotates token on valid refresh token")
        void refresh_success() {
            User user = User.builder()
                    .id("user-001").email("alice@example.com")
                    .username("alice").role(User.Role.USER)
                    .enabled(true).failedLoginAttempts(0).build();

            RefreshToken stored = RefreshToken.builder()
                    .id("rt-001")
                    .tokenHash("hashed-rt")
                    .user(user)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build();

            var req = new RefreshRequest();
            req.setRefreshToken("raw-refresh-token");

            when(jwtTokenService.hashRefreshToken("raw-refresh-token")).thenReturn("hashed-rt");
            when(refreshTokenRepository.findByTokenHash("hashed-rt"))
                    .thenReturn(Optional.of(stored));
            when(jwtTokenService.generateAccessToken(eq(user), anyLong())).thenReturn("new-access");
            when(jwtTokenService.generateRefreshToken()).thenReturn("new-raw-refresh");
            when(jwtTokenService.hashRefreshToken("new-raw-refresh")).thenReturn("new-hashed-rt");
            when(jwtTokenService.refreshTokenExpirySeconds()).thenReturn(604800L);
            when(refreshTokenRepository.findByUserIdOrderByCreatedAtAsc(anyString()))
                    .thenReturn(List.of());
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AuthResponse response = authService.refresh(req, "127.0.0.1", "agent");

            assertThat(response.getAccessToken()).isEqualTo("new-access");
            assertThat(stored.isRevoked()).isTrue(); // old token revoked
        }

        @Test
        @DisplayName("throws and revokes all tokens on reuse attempt")
        void refresh_reuse_detected() {
            User user = User.builder().id("user-001").build();
            RefreshToken revoked = RefreshToken.builder()
                    .id("rt-001").tokenHash("hashed").user(user)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(true).build();

            var req = new RefreshRequest();
            req.setRefreshToken("raw");

            when(jwtTokenService.hashRefreshToken("raw")).thenReturn("hashed");
            when(refreshTokenRepository.findByTokenHash("hashed"))
                    .thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> authService.refresh(req, "127.0.0.1", "agent"))
                    .isInstanceOf(AuthException.class)
                    .hasMessageContaining("invalid");

            verify(refreshTokenRepository).revokeAllForUser(eq("user-001"), any(Instant.class));
        }
    }
}
