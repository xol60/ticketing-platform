package com.ticketing.pricing.kafka;

import com.ticketing.common.events.*;
import com.ticketing.pricing.domain.repository.EventPriceRuleRepository;
import com.ticketing.pricing.service.PricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PricingCommandConsumer {

    private final PricingService            pricingService;
    private final EventPriceRuleRepository  ruleRepository;

    @KafkaListener(
            topics = Topics.PRICING_LOCK_CMD,
            groupId = "pricing-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPriceLockCommand(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof PriceLockCommand cmd)) {
                log.warn("Unexpected type on {}: {}", Topics.PRICING_LOCK_CMD, record.value().getClass().getSimpleName());
                ack.acknowledge();
                return;
            }
            log.info("PriceLockCommand: sagaId={} ticketId={} userPrice={} confirmed={}",
                    cmd.getSagaId(), cmd.getTicketId(), cmd.getUserPrice(), cmd.isConfirmed());
            pricingService.lockPrice(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing PriceLockCommand: {}", e.getMessage(), e);
            // Do not ack — retry
        }
    }

    @KafkaListener(
            topics = Topics.TICKET_RESERVED,
            groupId = "pricing-service-ticket-reserved",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTicketReserved(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReservedEvent e && e.getEventId() != null) {
                ruleRepository.incrementSoldTickets(e.getEventId());
                log.debug("soldTickets++ for eventId={} ticketId={}", e.getEventId(), e.getTicketId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReservedEvent for soldTickets: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = Topics.TICKET_RELEASED,
            groupId = "pricing-service-ticket-released",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTicketReleased(ConsumerRecord<String, DomainEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof TicketReleasedEvent e && e.getEventId() != null) {
                ruleRepository.decrementSoldTickets(e.getEventId());
                log.debug("soldTickets-- for eventId={} ticketId={}", e.getEventId(), e.getTicketId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing TicketReleasedEvent for soldTickets: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }
}
