package com.ticketing.auth.controller;

import com.ticketing.auth.dto.request.LoginRequest;
import com.ticketing.auth.dto.request.RefreshRequest;
import com.ticketing.auth.dto.request.RegisterRequest;
import com.ticketing.auth.dto.response.AuthResponse;
import com.ticketing.auth.dto.response.UserResponse;
import com.ticketing.auth.service.AuthService;
import com.ticketing.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/auth/register
     * Public — no JWT required.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        UserResponse user = authService.register(request);
        return ApiResponse.ok(user);
    }

    /**
     * POST /api/auth/login
     * Public — returns access + refresh token pair.
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip         = resolveIp(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");
        AuthResponse auth = authService.login(request, ip, deviceInfo);
        return ApiResponse.ok(auth);
    }

    /**
     * POST /api/auth/refresh
     * Public — rotates refresh token, issues new access token.
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest) {
        String ip         = resolveIp(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");
        AuthResponse auth = authService.refresh(request, ip, deviceInfo);
        return ApiResponse.ok(auth);
    }

    /**
     * POST /api/auth/logout
     * Authenticated (via gateway X-User-Id header).
     * Revokes current access token and optionally the refresh token.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader("X-User-Id")        String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false)     RefreshRequest body) {
        String accessToken  = extractBearer(authHeader);
        String refreshToken = body != null ? body.getRefreshToken() : null;
        authService.logout(userId, accessToken, refreshToken);
        return ApiResponse.ok(null);
    }

    /**
     * POST /api/auth/logout-all
     * Revokes ALL active sessions for the user.
     */
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(
            @RequestHeader("X-User-Id") String userId) {
        authService.logoutAll(userId);
        return ApiResponse.ok(null);
    }

    /**
     * GET /api/auth/me
     * Returns the authenticated user's profile.
     */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(
            @RequestHeader("X-User-Id") String userId) {
        return ApiResponse.ok(authService.getUser(userId));
    }

    // ── Internal endpoints (called by other services, not via Gateway) ────────

    /**
     * GET /auth/internal/users/{userId}
     * Called by internal services to look up a user — no auth check,
     * protected by network policy.
     */
    @GetMapping("/internal/users/{userId}")
    public ResponseEntity<UserResponse> getInternalUser(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(authService.getUser(userId));
        } catch (Exception ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String extractBearer(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
