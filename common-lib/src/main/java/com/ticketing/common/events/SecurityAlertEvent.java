package com.ticketing.common.events;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Published by auth-service when a security anomaly is detected.
 *
 * Current alert types:
 *   REFRESH_TOKEN_REUSE — a refresh token that was already rotated/revoked
 *                         has been presented again, indicating token theft.
 */
@Getter
@Setter
@NoArgsConstructor
public class SecurityAlertEvent extends DomainEvent {

    private String alertType;   // e.g. REFRESH_TOKEN_REUSE
    private String userId;
    private String userEmail;
    private String ipAddress;
    private String deviceInfo;

    public SecurityAlertEvent(String traceId,
                               String userId,
                               String userEmail,
                               String alertType,
                               String ipAddress,
                               String deviceInfo) {
        super(traceId, null);
        this.alertType  = alertType;
        this.userId     = userId;
        this.userEmail  = userEmail;
        this.ipAddress  = ipAddress;
        this.deviceInfo = deviceInfo;
    }
}
