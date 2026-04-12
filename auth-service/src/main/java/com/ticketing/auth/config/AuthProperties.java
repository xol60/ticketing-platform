package com.ticketing.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private Token token = new Token();

    @Data
    public static class Jwt {
        private String secret = "changeme_use_a_real_secret_in_production_256bits";
        private long   accessTokenExpirySeconds  = 900;      // 15 min
        private long   refreshTokenExpirySeconds = 604800;   // 7 days
        private String issuer = "ticketing-platform";
    }

    @Data
    public static class Token {
        /** Max active refresh tokens per user (oldest evicted on exceed) */
        private int maxRefreshTokensPerUser = 5;
        /** How long to keep revocation records in Redis (seconds) */
        private long revocationTtlSeconds = 86400; // 24 h
    }
}
