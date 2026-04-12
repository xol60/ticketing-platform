package com.ticketing.auth.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class UserResponse {
    private String  id;
    private String  email;
    private String  username;
    private String  role;
    private String  tenantId;
    private boolean enabled;
    private boolean emailVerified;
    private Instant createdAt;
}
