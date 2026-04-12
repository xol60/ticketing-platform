package com.ticketing.ticket.kafka;

import com.ticketing.common.events.*;
import com.ticketing.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketCommandConsumer {

    private final TicketService ticketService;

    @KafkaListener(topics = Topics.TICKET_RESERVE_CMD, groupId = "ticket-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onReserveCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReserveCommand cmd) {
                log.info("Received RESERVE cmd ticketId={} sagaId={}", cmd.getTicketId(), cmd.getSagaId());
                ticketService.handleReserveCommand(cmd);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing RESERVE cmd key={}", record.key(), e);
            ack.acknowledge(); // acknowledge to avoid reprocessing poison pills; DLQ can be wired here
        }
    }

    @KafkaListener(topics = Topics.TICKET_CONFIRM_CMD, groupId = "ticket-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onConfirmCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketConfirmCommand cmd) {
                log.info("Received CONFIRM cmd ticketId={} sagaId={}", cmd.getTicketId(), cmd.getSagaId());
                ticketService.handleConfirmCommand(cmd);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing CONFIRM cmd key={}", record.key(), e);
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = Topics.TICKET_RELEASE_CMD, groupId = "ticket-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onReleaseCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReleaseCommand cmd) {
                log.info("Received RELEASE cmd ticketId={} sagaId={}", cmd.getTicketId(), cmd.getSagaId());
                ticketService.handleReleaseCommand(cmd);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing RELEASE cmd key={}", record.key(), e);
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = Topics.SAGA_COMPENSATE, groupId = "ticket-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onCompensate(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof SagaCompensateEvent event) {
                log.info("Received COMPENSATE event orderId={} ticketId={}", event.getOrderId(), event.getTicketId());
                ticketService.handleCompensation(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing COMPENSATE key={}", record.key(), e);
            ack.acknowledge();
        }
    }
}
