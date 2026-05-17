package com.ticketing.ticket.outbox;

import com.ticketing.common.outbox.OutboxDrainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketOutboxScheduler {

    private static final int BATCH_SIZE = 50;

    private final OutboxDrainer drainer;

    @Scheduled(fixedDelay = 5_000)
    public void drain() {
        try {
            drainer.drainOnce(BATCH_SIZE);
        } catch (Exception ex) {
            log.error("Outbox drain failed: {}", ex.getMessage(), ex);
        }
    }
}
