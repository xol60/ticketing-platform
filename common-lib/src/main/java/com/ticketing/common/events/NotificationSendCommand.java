package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class NotificationSendCommand extends DomainEvent {
    private String type;       // EMAIL, PUSH, ADMIN_ALERT
    private String recipient;  // userId or admin email
    private String subject;
    private String body;
    private String referenceId;

    public NotificationSendCommand(String traceId, String sagaId,
                                   String type, String recipient,
                                   String subject, String body, String referenceId) {
        super(traceId, sagaId);
        this.type        = type;
        this.recipient   = recipient;
        this.subject     = subject;
        this.body        = body;
        this.referenceId = referenceId;
    }
}
