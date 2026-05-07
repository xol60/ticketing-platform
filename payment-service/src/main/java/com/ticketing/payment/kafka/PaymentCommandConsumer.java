package com.ticketing.payment.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.PaymentCancelCommand;
import com.ticketing.common.events.PaymentChargeCommand;
import com.ticketing.common.events.Topics;
import com.ticketing.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes all commands destined for the payment service from the single
 * {@link Topics#PAYMENT_CMD} topic.
 *
 * <h3>Why a single topic?</h3>
 * {@link PaymentChargeCommand} and {@link PaymentCancelCommand} for the same order
 * must be processed in the order they were produced. Kafka guarantees this only
 * <em>within one partition of one topic</em>. Both commands are keyed by {@code orderId},
 * so they always hash to the same partition — and because there is only one consumer
 * thread per partition, they are consumed sequentially.
 *
 * <p>If they were on separate topics (e.g. {@code payment.charge.cmd} and
 * {@code payment.cancel.cmd}), each topic would have its own consumer thread.
 * The cancel consumer could process its message before the charge consumer does,
 * causing {@link PaymentService#cancelPayment} to find no payment record and
 * silently drop the cancellation — leaving the customer billed with no refund.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCommandConsumer {

    private final PaymentService paymentService;

    @KafkaListener(topics = Topics.PAYMENT_CMD, containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        log.info("[Consumer] Received payment command on topic={} key={} type={}",
                record.topic(), record.key(),
                record.value() == null ? "null" : record.value().getClass().getSimpleName());
        try {
            switch (record.value()) {
                case PaymentChargeCommand cmd -> paymentService.processPayment(cmd);
                case PaymentCancelCommand  cmd -> paymentService.cancelPayment(cmd);
                case null -> log.warn("[Consumer] Null payload on payment.cmd key={}", record.key());
                default   -> log.warn("[Consumer] Unknown command type={} key={}",
                                      record.value().getClass().getName(), record.key());
            }
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("[Consumer] Error processing payment command key={}: {}", record.key(), ex.getMessage(), ex);
            // Acknowledge to avoid poison-pill loop; critical failures are handled
            // inside the service (DLQ, admin notifications).
            ack.acknowledge();
        }
    }
}
