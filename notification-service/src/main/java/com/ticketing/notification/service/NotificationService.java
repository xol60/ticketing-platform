package com.ticketing.notification.service;

import com.ticketing.common.events.NotificationSendCommand;
import com.ticketing.common.events.PaymentFailedEvent;
import com.ticketing.common.events.SecurityAlertEvent;
import com.ticketing.common.events.TicketConfirmedEvent;
import com.ticketing.notification.domain.model.NotificationLog;
import com.ticketing.notification.domain.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Simulates email/push sending by logging and persisting to the DB.
 * No actual outbound I/O is performed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationLogRepository repository;

    // -----------------------------------------------------------------------
    // Ticket confirmed → success notification to user
    // -----------------------------------------------------------------------

    @Transactional
    public void sendTicketConfirmed(TicketConfirmedEvent event) {
        log.info("[Notification] sendTicketConfirmed: orderId={} userId={} ticketId={}",
                event.getOrderId(), event.getUserId(), event.getTicketId());

        String subject = "Your ticket is confirmed!";
        String body = String.format(
                "Hi %s, your ticket (id=%s) for order %s has been confirmed successfully.",
                event.getUserId(), event.getTicketId(), event.getOrderId());

        NotificationLog entry = NotificationLog.builder()
                .id(UUID.randomUUID())
                .type("EMAIL")
                .recipient(event.getUserId())
                .subject(subject)
                .body(body)
                .referenceId(event.getOrderId())
                .status("SENT")
                .build();
        repository.save(entry);

        log.info("[Notification] EMAIL logged id={} recipient={}", entry.getId(), entry.getRecipient());
    }

    // -----------------------------------------------------------------------
    // Payment DLQ → admin alert
    // -----------------------------------------------------------------------

    @Transactional
    public void sendAdminAlert(PaymentFailedEvent event) {
        log.warn("[Notification] sendAdminAlert: orderId={} userId={} reason={}",
                event.getOrderId(), event.getUserId(), event.getFailureReason());

        String subject = "ADMIN ALERT: Payment failed for order " + event.getOrderId();
        String body = String.format(
                "Payment failed for orderId=%s userId=%s after %d attempts. Reason: %s",
                event.getOrderId(), event.getUserId(),
                event.getAttemptCount(), event.getFailureReason());

        NotificationLog entry = NotificationLog.builder()
                .id(UUID.randomUUID())
                .type("ADMIN_ALERT")
                .recipient("admin@ticketing.com")
                .subject(subject)
                .body(body)
                .referenceId(event.getOrderId())
                .status("SENT")
                .build();
        repository.save(entry);

        log.warn("[Notification] ADMIN_ALERT logged id={}", entry.getId());
    }

    // -----------------------------------------------------------------------
    // Generic notification command handler
    // -----------------------------------------------------------------------

    @Transactional
    public void sendGeneric(NotificationSendCommand cmd) {
        log.info("[Notification] sendGeneric: type={} recipient={} referenceId={}",
                cmd.getType(), cmd.getRecipient(), cmd.getReferenceId());

        NotificationLog entry = NotificationLog.builder()
                .id(UUID.randomUUID())
                .type(cmd.getType())
                .recipient(cmd.getRecipient())
                .subject(cmd.getSubject())
                .body(cmd.getBody())
                .referenceId(cmd.getReferenceId())
                .status("SENT")
                .build();
        repository.save(entry);

        log.info("[Notification] {} logged id={} recipient={}", cmd.getType(), entry.getId(), entry.getRecipient());
    }

    // -----------------------------------------------------------------------
    // Security alert → notify user AND admin
    // -----------------------------------------------------------------------

    @Transactional
    public void sendSecurityAlert(SecurityAlertEvent event) {
        log.warn("[Notification] sendSecurityAlert: userId={} type={} ip={}",
                event.getUserId(), event.getAlertType(), event.getIpAddress());

        // Alert to the affected user
        String userSubject = "Security alert: suspicious activity on your account";
        String userBody = String.format(
                "We detected suspicious activity on your account (userId=%s). " +
                "All active sessions have been invalidated as a precaution. " +
                "If this was not you, please change your password immediately. " +
                "Details: type=%s ip=%s device=%s",
                event.getUserId(), event.getAlertType(),
                event.getIpAddress(), event.getDeviceInfo());

        NotificationLog userAlert = NotificationLog.builder()
                .id(UUID.randomUUID())
                .type("EMAIL")
                .recipient(event.getUserId())
                .subject(userSubject)
                .body(userBody)
                .referenceId(event.getEventId())
                .status("SENT")
                .build();

        // Alert to admin
        String adminSubject = "SECURITY ALERT: " + event.getAlertType() + " for userId=" + event.getUserId();
        String adminBody = String.format(
                "Security event detected.\n" +
                "Type:   %s\nUserId: %s\nEmail:  %s\nIP:     %s\nDevice: %s\nTime:   %s",
                event.getAlertType(), event.getUserId(), event.getUserEmail(),
                event.getIpAddress(), event.getDeviceInfo(), event.getOccurredAt());

        NotificationLog adminAlert = NotificationLog.builder()
                .id(UUID.randomUUID())
                .type("ADMIN_ALERT")
                .recipient("admin@ticketing.com")
                .subject(adminSubject)
                .body(adminBody)
                .referenceId(event.getEventId())
                .status("SENT")
                .build();

        repository.saveAll(List.of(userAlert, adminAlert));
        log.warn("[Notification] SECURITY_ALERT logged for userId={}", event.getUserId());
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NotificationLog> getByRecipient(String recipient) {
        return repository.findByRecipientOrderByCreatedAtDesc(recipient);
    }
}
