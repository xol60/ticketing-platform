package com.ticketing.payment.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.PaymentChargeCommand;
import com.ticketing.common.events.Topics;
import com.ticketing.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCommandConsumer {

    private final PaymentService paymentService;

    @KafkaListener(topics = Topics.PAYMENT_CHARGE_CMD, containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentChargeCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        log.info("[Consumer] Received message on topic={} key={}", record.topic(), record.key());
        try {
            if (!(record.value() instanceof PaymentChargeCommand cmd)) {
                log.warn("[Consumer] Unexpected event type: {}", record.value().getClass().getName());
                ack.acknowledge();
                return;
            }
            paymentService.processPayment(cmd);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("[Consumer] Error processing PaymentChargeCommand key={}: {}", record.key(), ex.getMessage(), ex);
            // Acknowledge to avoid poison-pill loop; DLQ handling is done in service
            ack.acknowledge();
        }
    }
}
