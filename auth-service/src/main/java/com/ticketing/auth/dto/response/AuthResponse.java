package com.ticketing.auth.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private long   accessTokenExpiresIn;
    private String tokenType;
    private String userId;
    private String username;
    private String role;
}
