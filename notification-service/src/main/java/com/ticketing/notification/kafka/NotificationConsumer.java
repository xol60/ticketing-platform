package com.ticketing.notification.kafka;

import com.ticketing.common.events.*;
import com.ticketing.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = Topics.TICKET_CONFIRMED, groupId = "notification-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketConfirmed(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketConfirmedEvent event) {
                log.info("Received TICKET_CONFIRMED ticketId={} orderId={}", event.getTicketId(), event.getOrderId());
                notificationService.sendTicketConfirmed(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TICKET_CONFIRMED key={}", record.key(), e);
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_DLQ, groupId = "notification-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentDlq(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof PaymentFailedEvent event) {
                log.warn("Received PAYMENT_DLQ orderId={} reason={}", event.getOrderId(), event.getFailureReason());
                notificationService.sendAdminAlert(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PAYMENT_DLQ key={}", record.key(), e);
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = Topics.NOTIFICATION_SEND, groupId = "notification-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onNotificationSend(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof NotificationSendCommand cmd) {
                log.info("Received NOTIFICATION_SEND type={} recipient={}", cmd.getType(), cmd.getRecipient());
                notificationService.sendGeneric(cmd);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing NOTIFICATION_SEND key={}", record.key(), e);
            ack.acknowledge();
        }
    }
}
