package com.ticketing.auth.kafka;

import com.ticketing.common.events.DomainEvent;
import com.ticketing.common.events.SecurityAlertEvent;
import com.ticketing.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes security-related events from auth-service to Kafka.
 *
 * The message key is userId so all alerts for the same user land on the
 * same partition — consumers see them in the order they were produced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventProducer {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    public void publishAlert(SecurityAlertEvent event) {
        kafkaTemplate.send(Topics.AUTH_SECURITY_ALERT, event.getUserId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish security alert userId={} type={}",
                                event.getUserId(), event.getAlertType(), ex);
                    } else {
                        log.info("Security alert published userId={} type={} offset={}",
                                event.getUserId(), event.getAlertType(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
