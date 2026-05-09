package com.ticketing.auth.exception;

import com.ticketing.common.exception.ErrorCode;
import com.ticketing.common.exception.TicketingException;

public class AuthException extends TicketingException {

    public AuthException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static AuthException invalidCredentials() {
        return new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid email/username or password");
    }

    public static AuthException accountDisabled() {
        return new AuthException(ErrorCode.AUTH_ACCOUNT_DISABLED, "Account is disabled");
    }

    public static AuthException accountLocked(long secondsRemaining) {
        return new AuthException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                "Account is locked. Try again in " + secondsRemaining + " seconds");
    }

    public static AuthException tokenExpired() {
        return new AuthException(ErrorCode.AUTH_TOKEN_EXPIRED, "Token has expired");
    }

    public static AuthException tokenInvalid(String reason) {
        return new AuthException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid token: " + reason);
    }

    public static AuthException emailTaken() {
        return new AuthException(ErrorCode.AUTH_EMAIL_TAKEN, "Email already registered");
    }

    public static AuthException usernameTaken() {
        return new AuthException(ErrorCode.AUTH_USERNAME_TAKEN, "Username already taken");
    }
}
