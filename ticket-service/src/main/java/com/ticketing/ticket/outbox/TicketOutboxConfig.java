package com.ticketing.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.outbox.OutboxAwarePublisher;
import com.ticketing.common.outbox.OutboxDrainer;
import com.ticketing.ticket.domain.model.TicketOutboxEvent;
import com.ticketing.ticket.domain.repository.TicketOutboxEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TicketOutboxConfig {

    @Bean
    public TransactionTemplate outboxTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    public OutboxAwarePublisher outboxAwarePublisher(KafkaTemplate<String, DomainEvent> kafkaTemplate,
                                                      ObjectMapper objectMapper,
                                                      TransactionTemplate outboxTransactionTemplate,
                                                      TicketOutboxEventRepository repository) {
        return new OutboxAwarePublisher(
                kafkaTemplate,
                objectMapper,
                outboxTransactionTemplate,
                TicketOutboxEvent::new,
                entry -> repository.save((TicketOutboxEvent) entry));
    }

    @Bean
    public OutboxDrainer ticketOutboxDrainer(KafkaTemplate<String, DomainEvent> kafkaTemplate,
                                              ObjectMapper objectMapper,
                                              TicketOutboxEventRepository repository) {
        return new OutboxDrainer(
                kafkaTemplate,
                objectMapper,
                batchSize -> repository.findUnpublishedBatch(PageRequest.of(0, batchSize)),
                entry -> repository.save((TicketOutboxEvent) entry));
    }
}
