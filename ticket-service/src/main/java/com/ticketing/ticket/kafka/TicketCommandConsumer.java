package com.ticketing.ticket.kafka;

import com.ticketing.common.events.*;
import com.ticketing.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes all commands destined for the ticket service from the single
 * {@link Topics#TICKET_CMD} topic.
 *
 * <h3>Why a single topic?</h3>
 * {@link TicketReserveCommand}, {@link TicketConfirmCommand}, and
 * {@link TicketReleaseCommand} for the same order must be processed in the order
 * they were produced. Kafka guarantees this only <em>within one partition of one
 * topic</em>. All three commands are keyed by {@code orderId}, so they always hash
 * to the same partition — and because there is only one consumer thread per partition,
 * they are consumed sequentially.
 *
 * <p>The critical race this prevents:
 * <pre>
 *   Saga sends TicketConfirmCommand  (saga step 5, after payment)
 *   Saga watchdog fires, sends TicketReleaseCommand (compensation)
 *
 *   Split topics → two independent consumer threads → release could be processed
 *   first → confirm finds AVAILABLE ticket → publishes spurious TicketReleasedEvent
 *   → saga triggers payment refund for a charge that didn't need reversing.
 *
 *   Single topic → same partition → confirm always processed before release for
 *   the same orderId, regardless of consumer lag.
 * </pre>
 *
 * <p>Saga compensation also flows through this same topic: when a step fails, the
 * orchestrator sends a {@link TicketReleaseCommand} on {@code ticket.cmd} rather than
 * using a separate compensation topic, so the release is ordered correctly relative to
 * any in-flight confirm.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketCommandConsumer {

    private final TicketService ticketService;

    @KafkaListener(topics = Topics.TICKET_CMD, groupId = "ticket-service",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onTicketCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        log.info("[Consumer] Received ticket command topic={} key={} type={}",
                record.topic(), record.key(),
                record.value() == null ? "null" : record.value().getClass().getSimpleName());
        try {
            switch (record.value()) {
                case TicketReserveCommand cmd -> {
                    log.info("Received RESERVE cmd ticketId={} sagaId={}", cmd.getTicketId(), cmd.getSagaId());
                    ticketService.handleReserveCommand(cmd);
                }
                case TicketConfirmCommand cmd -> {
                    log.info("Received CONFIRM cmd ticketId={} sagaId={}", cmd.getTicketId(), cmd.getSagaId());
                    ticketService.handleConfirmCommand(cmd);
                }
                case TicketReleaseCommand cmd -> {
                    log.info("Received RELEASE cmd ticketId={} sagaId={}", cmd.getTicketId(), cmd.getSagaId());
                    ticketService.handleReleaseCommand(cmd);
                }
                case null ->
                    log.warn("[Consumer] Null payload on ticket.cmd key={}", record.key());
                default ->
                    log.warn("[Consumer] Unknown command type={} key={}",
                            record.value().getClass().getName(), record.key());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[Consumer] Error processing ticket command key={}: {}", record.key(), e.getMessage(), e);
            // Acknowledge to avoid poison-pill loop; idempotency guards inside the
            // service make it safe to retry via Kafka re-delivery if needed.
            ack.acknowledge();
        }
    }
}
