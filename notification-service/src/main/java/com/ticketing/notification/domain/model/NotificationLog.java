package com.ticketing.notification.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 50)
    private String type;          // EMAIL, PUSH, ADMIN_ALERT

    @Column(nullable = false)
    private String recipient;

    @Column
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(nullable = false, length = 20)
    private String status;        // SENT, FAILED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
